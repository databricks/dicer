use std::any::{Any, TypeId};
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex, MutexGuard};

/// An extension trait that adds an `unwrap_ref_or_default` method to [`Option`].
pub trait UnwrapRefOrDefault<'a> {
    type T;

    /// Returns a reference to the value contained in the `Option` if it is `Some`, or a reference
    /// to a default instance of the contained type if it is `None`.
    ///
    /// This is analogous to [`Option::unwrap_or_default`], except that that method is generally not
    /// useful when the `Option` is wrapping a reference, since reference types typically don't
    /// implement [`Default`] even if the underlying type does.
    ///
    /// This is particularly useful when accessing a submessage within a proto that's being accessed
    /// behind a reference, since typically we prefer to treat the absence of a field as equivalent
    /// to the default value of that field, but prost's generated proto code does not provide
    /// accessors that make it easy to do so.
    ///
    /// This means that instead of writing code like this:
    ///
    /// ```ignore
    /// static DEFAULT_REDIRECT: LazyLock<RedirectP> = LazyLock::new(RedirectP::default);
    /// let raw_redirect: &str =
    ///     proto.redirect.as_ref()
    ///         .unwrap_or(&*DEFAULT_REDIRECT)
    ///         .address
    ///         .as_ref()
    ///         .map(|s: &String| s as &str)
    ///         .unwrap_or("");
    /// ```
    ///
    /// You can write code like this:
    ///
    /// ```ignore
    /// let raw_redirect: &str =
    ///     proto.redirect.unwrap_ref_or_default().address.unwrap_ref_or_default();
    /// ```
    fn unwrap_ref_or_default(self) -> &'a Self::T;
}

impl<'a, T: Default + Sync + 'static> UnwrapRefOrDefault<'a> for Option<&'a T> {
    type T = T;

    fn unwrap_ref_or_default(self) -> &'a T {
        self.unwrap_or_else(get_default_ref)
    }
}

impl<'a, T: Default + Sync + 'static> UnwrapRefOrDefault<'a> for &'a Option<T> {
    type T = T;

    fn unwrap_ref_or_default(self) -> &'a T {
        self.as_ref().unwrap_or_else(get_default_ref)
    }
}

/// A map holding a static reference to a default instance of each type that has been used.
///
/// We need to key the map by [`TypeId`] instead of just putting a `LazyLock<T>` in the body of
/// [`get_default_ref`], because Rust does not allow the use of outer generic types in static
/// variables.
///
/// It's a little unfortunate that this means we need to perform a lock acquisition and a hash map
/// lookup in the path where the `Option` is `None`, but the code that we would need to write if we
/// didn't have the `unwrap_ref_or_default` helper is so unreasonably cumbersome that it seems worth
/// it.
///
/// Fortunately, we generally only use this helper for proto fields, where the absence of a sub-
/// message that's used in this way is very rare, so the lock acquisition and hash map lookup path
/// is also rare.
static DEFAULTS: LazyLock<Mutex<HashMap<TypeId, &'static (dyn Any + Sync)>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Returns a reference to a default instance of `T` with static lifetime, lazily creating a default
/// instance on the first call for a given type.
fn get_default_ref<'a, T: Default + Sync + 'static>() -> &'a T {
    let type_id = TypeId::of::<T>();
    {
        // We use `unwrap` on the lock result because the code in the critical section below should
        // never panic, so if the mutex ends up in the poisoned state unexpectedly, we would rather
        // fail fast and propagate the panic as recommended in
        // https://doc.rust-lang.org/std/sync/struct.Mutex.html#poisoning.
        let defaults: MutexGuard<HashMap<TypeId, &'static (dyn Any + Sync)>> =
            DEFAULTS.lock().unwrap();
        if let Some(default_ref) = defaults.get(&type_id) {
            // There's already an existing default instance of this type that we will reuse.
            return <dyn Any>::downcast_ref::<T>(*default_ref).unwrap();
        }
    }

    // This type has not been used before, so construct a new default instance.
    //
    // It's important that we not hold the mutex while invoking the default method, because the
    // default method may itself call `unwrap_ref_or_default`, which would then lead to deadlock.
    // Additionally, we don't want to poison the mutex if the default initializer panics.
    let default = T::default();

    {
        let mut defaults: MutexGuard<HashMap<TypeId, &'static (dyn Any + Sync)>> =
            DEFAULTS.lock().unwrap();
        // It's possible that a concurrent call inserted a default for this type into the map
        // while we had released the mutex, in which case we will not execute the `or_insert_with`
        // lambda below, we will destruct the `default` object we constructed above, and we will
        // reuse the existing default.
        <dyn Any>::downcast_ref::<T>(*defaults.entry(type_id).or_insert_with(|| {
            // We need to move the default instance that we constructed above onto the heap and
            // then "leak" it so that it (proveably) has static lifetime.
            Box::leak(Box::new(default)) as &'static (dyn Any + Sync)
        }))
        .unwrap()
    }
}
