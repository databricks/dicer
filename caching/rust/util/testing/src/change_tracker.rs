use std::ops::Sub;

/// A test helper class for tracking changes in the value computed by a function.
///
/// This class is useful, for example, to test that a metric has been incremented by a specified
/// amount after an operation. It captures an initial value when created and calculates the
/// difference against the current value when checked.
///
/// # Examples
///
/// ```
/// use raf_metrics::gauge;
/// use caching_rust_util_testing::change_tracker::ChangeTracker;
///
/// gauge!(TEST_GAUGE, "A test gauge for demonstrating ChangeTracker");
///
/// // Set an initial value for the gauge, and start tracking changes.
/// TEST_GAUGE.set(10);
///
/// let change_tracker = ChangeTracker::new(|| TEST_GAUGE.get());
///
/// // Perform some operations that modify the gauge.
/// TEST_GAUGE.set(15);
/// assert_eq!(change_tracker.total_change(), 5);
///
/// TEST_GAUGE.set(7);
/// assert_eq!(change_tracker.total_change(), -3);
/// ```
pub struct ChangeTracker<T, F>
where
    T: Sub<Output = T> + Copy,
    F: Fn() -> T,
{
    get_value_func: F,
    initial_value: T,
}

impl<T, F> ChangeTracker<T, F>
where
    T: Sub<Output = T> + Copy,
    F: Fn() -> T,
{
    /// Creates a new `ChangeTracker` instance.
    ///
    /// # Arguments
    ///
    /// * `get_value`: A closure that returns the current value to track.
    ///
    /// # Returns
    ///
    /// A new `ChangeTracker` instance initialized with the current value.
    pub fn new(get_value_func: F) -> Self {
        let initial_value = get_value_func();
        ChangeTracker {
            get_value_func,
            initial_value,
        }
    }

    /// Computes the change in the tracked value since the creation of the `ChangeTracker`.
    ///
    /// # Returns
    ///
    /// The difference between the current value and the initial value.
    pub fn total_change(&self) -> T {
        let current_value: T = (self.get_value_func)();
        current_value - self.initial_value
    }
}
