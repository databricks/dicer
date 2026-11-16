//! A registry to coordinate graceful shutdown of registered [`DrainableFuture`]s. Library
//! components register drain actions with [`GLOBAL_DRAINING_REGISTRY`], which runs them
//! sequentially in registration order during graceful shutdown. The application must call
//! `GLOBAL_DRAINING_REGISTRY.drain_and_await_completion()` from its own shutdown handling (for
//! example, on SIGTERM) to trigger draining.

use std::future::Future;
use std::sync::{LazyLock, Mutex, MutexGuard};
use std::time::Duration;

use futures::future::BoxFuture;
use futures::FutureExt;
use tokio::time::timeout;
use tracing::{info, warn};

/// Global registry of components that participate in graceful shutdown.
pub static GLOBAL_DRAINING_REGISTRY: LazyLock<DrainingRegistry> =
    LazyLock::new(DrainingRegistry::new);

/// Identifies the group a component drains with. There is only one bucket, this enum exists only
/// for compatibility with internal code.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum GlobalDrainingBucket {
    /// Operations that prepare the application for shutdown.
    Preparation,
}

/// A named drain action. The factory is called during draining to create the future that performs
/// the drain.
pub struct DrainableFuture<F> {
    /// Name of the component, used in logs.
    name: String,
    /// Creates the future that drains the component.
    factory: F,
}

impl<F> DrainableFuture<F> {
    /// Creates a drain action named `name` whose drain runs the future returned by `factory`.
    pub fn new(name: impl Into<String>, factory: F) -> Self {
        Self {
            name: name.into(),
            factory,
        }
    }
}

/// Registry of drain actions, drained once during graceful shutdown. Use
/// [`GLOBAL_DRAINING_REGISTRY`].
pub struct DrainingRegistry {
    /// Registered components, or `None` once draining has started.
    components: Mutex<Option<Vec<RegisteredComponent>>>,
}

impl DrainingRegistry {
    /// Registers `component` to be drained, in registration order.
    ///
    /// Returns `true` if the component was registered, or `false` (dropping it without running it)
    /// if draining has already started. Component names are only used for debugging, and don't need
    /// to be unique. `bucket_id` is ignored but kept for interface compatibility with internal
    /// code.
    ///
    /// # Example
    ///
    /// ```rust
    /// use raf_draining::{DrainableFuture, GlobalDrainingBucket, GLOBAL_DRAINING_REGISTRY};
    ///
    /// GLOBAL_DRAINING_REGISTRY.add_component_to_bucket(
    ///     DrainableFuture::new("my-component", || async {
    ///         println!("Cleaning up my component");
    ///     }),
    ///     GlobalDrainingBucket::Preparation,
    /// );
    /// ```
    pub fn add_component_to_bucket<F, Fut>(
        &self,
        component: DrainableFuture<F>,
        bucket_id: GlobalDrainingBucket,
    ) -> bool
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let DrainableFuture { name, factory } = component;
        let mut guard: MutexGuard<'_, Option<Vec<RegisteredComponent>>> = self
            .components
            .lock()
            .expect("draining registry components lock poisoned");
        let Some(components) = guard.as_mut() else {
            warn!("Not allowed to add draining component {name} during shutdown. Ignoring.");
            return false;
        };
        components.push(RegisteredComponent {
            name,
            factory: Box::new(move || factory().boxed()),
        });
        true
    }

    /// Starts draining and waits for every registered component to finish within
    /// `timeout_duration`.
    ///
    /// Components drain sequentially in registration order under one overall timeout. Returns
    /// `true` if all components finished, or `false` if the timeout elapsed first. On timeout, the
    /// in-flight and remaining components are dropped.
    ///
    /// Once draining starts, subsequent drain calls return `true` immediately without waiting for
    /// an active drain or running any components.
    ///
    /// # Panics
    ///
    /// Propagates panics from component factories and drain futures.
    pub async fn drain_and_await_completion(&self, timeout_duration: Duration) -> bool {
        // Take the components so that later registrations are rejected. The lock is released
        // before draining, so draining components may call back into the registry.
        let taken: Option<Vec<RegisteredComponent>> = {
            let mut guard: MutexGuard<'_, Option<Vec<RegisteredComponent>>> = self
                .components
                .lock()
                .expect("draining registry components lock poisoned");
            guard.take()
        };
        let Some(components) = taken else {
            return true;
        };
        info!("Starting draining process for {} component(s)", components.len());

        let drain_all = async move {
            for component in components {
                component.drain().await;
            }
        };
        match timeout(timeout_duration, drain_all).await {
            Ok(_) => {
                info!("All components completed draining successfully");
                true
            }
            Err(_) => {
                warn!("Draining process timed out after {timeout_duration:?}");
                false
            }
        }
    }

    /// Creates an independent, empty registry for tests. Production code should use
    /// [`GLOBAL_DRAINING_REGISTRY`].
    pub fn new_for_test() -> Self {
        Self::new()
    }

    /// Creates an empty registry that accepts registrations.
    fn new() -> Self {
        Self {
            components: Mutex::new(Some(Vec::new())),
        }
    }
}

/// A drain action stored in a [`DrainingRegistry`].
struct RegisteredComponent {
    /// Name of the component, used in logs.
    name: String,
    /// Creates the future that drains the component.
    factory: Box<dyn FnOnce() -> BoxFuture<'static, ()> + Send>,
}

impl RegisteredComponent {
    /// Runs the component's drain action to completion.
    async fn drain(self) {
        info!("Draining {}", self.name);
        (self.factory)().await;
        info!("Completed draining {}", self.name);
    }
}
