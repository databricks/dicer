use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::{Duration, SystemTime};

use caching_rust_util::watch_cell::WatchCell;
use caching_rust_util::{caching_error_code, watch_cell, Clock};
use caching_rust_util_state_machine::{
    self as state_machine,
    StateMachineDriver,
    StateMachineOutput,
};

/// Duration after which a resource is considered stale if no heartbeat is received.
const HEARTBEAT_TIMEOUT: Duration = Duration::from_secs(30);

/// Generates assignments containing resources that have heartbeat activity.
/// Resources are automatically removed from assignments if they haven't sent
/// a heartbeat in the last 30 seconds.
///
/// This example demonstrates usage of the StateMachine API, including:
/// - Maintaining time-based state
/// - Scheduling periodic advance calls
/// - Generating actions based on time-based events
pub struct AssignmentGenerator {
    /// Copy of the assignment cell to which assignments are written.
    assignment_cell: WatchCell<Assignment>,

    /// State machine driver.
    driver: StateMachineDriver<StateMachine>,
}

impl AssignmentGenerator {
    /// Creates an assignment generator with an empty initial assignment.
    ///
    /// Note: The StateMachineDriver runs in a background task and stays alive
    /// as long as any references to the driver exist.
    pub fn new(clock: Clock) -> Self {
        let assignment_cell = WatchCell::new(Assignment::default());
        let state_machine = StateMachine::default();
        let driver = StateMachineDriver::new(
            clock,
            state_machine,
            caching_error_code::AlertOwnerTeam::CachingTeam,
        );
        // Bridge state machine actions into a WatchCell so callers can await updates
        // without holding the driver. Clone before `start`: the action-handler closure
        // is moved into the driver's background task and must own its captures.
        let assignment_cell_clone = assignment_cell.clone();
        let started: bool = driver.start(move |actions| {
            for action in actions {
                match action {
                    Action::SetAssignment(assignment) => {
                        assignment_cell_clone.set(assignment);
                    }
                }
            }
        });
        // Fresh driver; `false` only if `start` was already called.
        assert!(started);
        Self {
            assignment_cell,
            driver,
        }
    }

    /// Returns a reference to the latest assignment.
    pub fn assignment(&self) -> watch_cell::Ref<Assignment> {
        self.assignment_cell.get()
    }

    /// Blocks until an assignment with generation greater than
    /// `known_generation` is generated.
    pub async fn on_assignment_changed(&self, known_generation: u64) {
        self.assignment_cell.changed_since(known_generation).await;
    }

    /// Reports a resource heartbeat - this resource will be included in future
    /// assignments. Events are processed asynchronously by the state machine.
    pub fn on_resource_heartbeat(&self, resource: impl Into<String>) {
        self.driver
            .event_handler()
            .handle_event(Event::ResourceHeartbeat(resource.into()));
    }
}

/// Represents an assignment with a generation number and set of resources.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Assignment {
    pub generation: u64,
    pub resources: HashSet<String>,
}

// The generation of the assignment is used as its version in the WatchCell.
impl watch_cell::Versioned for Assignment {
    type Version = u64;

    fn version(&self) -> Self::Version {
        self.generation
    }
}

impl Assignment {
    pub fn new(generation: u64, resources: HashSet<String>) -> Self {
        Self {
            generation,
            resources,
        }
    }
}

//
// Internal implementation details below this point.
//

/// Events received by the state machine.
enum Event {
    /// A resource with the given name sent a heartbeat.
    ResourceHeartbeat(String),
}

/// Actions triggered by the state machine.
enum Action {
    /// The generator produced a new assignment that should be set on the
    /// watch cell.
    SetAssignment(Assignment),
}

#[derive(Default)]
struct StateMachine {
    /// Map from resource name to last heartbeat time.
    resource_heartbeats: HashMap<String, SystemTime>,

    /// Sorted map from heartbeat time to resource names (for efficient expiry).
    /// Multiple resources may have the same heartbeat time.
    heartbeat_times: BTreeMap<SystemTime, HashSet<String>>,

    /// The latest generated assignment (initially empty).
    latest_assignment: Assignment,
}

impl StateMachine {
    fn on_resource_heartbeat(&mut self, resource: String, now: SystemTime) {
        // Remove the resource from its old heartbeat time entry if it exists.
        if let Some(old_time) = self.resource_heartbeats.get(&resource)
            && let Some(resources) = self.heartbeat_times.get_mut(old_time)
        {
            resources.remove(&resource);
            if resources.is_empty() {
                self.heartbeat_times.remove(old_time);
            }
        }

        // Update the resource's heartbeat time.
        self.resource_heartbeats.insert(resource.clone(), now);

        // Add the resource to the new heartbeat time entry
        self.heartbeat_times
            .entry(now)
            .or_default()
            .insert(resource);
    }

    /// Removes stale resources that have not recently sent heartbeats.
    fn remove_stale_resources(&mut self, now: SystemTime) {
        let expiry_time = now
            .checked_sub(HEARTBEAT_TIMEOUT)
            .unwrap_or(SystemTime::UNIX_EPOCH);

        // Find all resources with heartbeats older than expiry_time.
        let stale_resources: Vec<String> = self
            .heartbeat_times
            .range(..expiry_time)
            .flat_map(|(_, resources)| resources.iter().cloned())
            .collect();

        // Remove stale resources from both maps.
        for resource in &stale_resources {
            if let Some(old_time) = self.resource_heartbeats.remove(resource)
                && let Some(resources) = self.heartbeat_times.get_mut(&old_time)
            {
                resources.retain(|r| r != resource);
                if resources.is_empty() {
                    self.heartbeat_times.remove(&old_time);
                }
            }
        }
    }

    /// Determines whether a new assignment is needed. Returns true iff. the
    /// resources in `latest_assignment` doesn't match those that have recently
    /// sent heartbeats.
    fn should_generate_assignment(&self) -> bool {
        let current_resources: HashSet<&String> = self.resource_heartbeats.keys().collect();
        let assigned_resources: HashSet<&String> =
            self.latest_assignment.resources.iter().collect();
        current_resources != assigned_resources
    }

    /// Generates a new assignment containing all resources that have recently
    /// sent heartbeats.
    fn generate_assignment(&mut self, output: &mut StateMachineOutput<Action>) {
        let generation = self.latest_assignment.generation + 1;
        let resources: HashSet<String> = self.resource_heartbeats.keys().cloned().collect();
        self.latest_assignment = Assignment::new(generation, resources);
        output.append_action(Action::SetAssignment(self.latest_assignment.clone()));
    }

    /// Schedules the next advance for when the oldest resource expires.
    fn schedule_next_advance(&self, output: &mut StateMachineOutput<Action>) {
        // Schedule the next advance for when the oldest resource expires.
        if let Some((oldest_time, _)) = self.heartbeat_times.iter().next()
            && let Some(next_expiry_time) = oldest_time.checked_add(HEARTBEAT_TIMEOUT)
        {
            output.ensure_advance_by(next_expiry_time);
        }
    }
}

impl state_machine::StateMachine for StateMachine {
    type Event = Event;
    type Action = Action;

    fn advance(
        &mut self,
        now: SystemTime,
        events: Vec<Self::Event>,
    ) -> StateMachineOutput<Self::Action> {
        let mut output = StateMachineOutput::new();

        // Process all events.
        for event in events {
            match event {
                Event::ResourceHeartbeat(resource) => {
                    self.on_resource_heartbeat(resource, now);
                }
            }
        }
        // After processing all events (including heartbeats), remove any stale
        // resources.
        self.remove_stale_resources(now);

        // Generate a new assignment if necessary.
        if self.should_generate_assignment() {
            self.generate_assignment(&mut output);
        }

        // Schedule the next advance for when the oldest resource expires.
        self.schedule_next_advance(&mut output);

        output
    }
}
