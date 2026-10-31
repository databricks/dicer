use std::hash::{Hash, Hasher};

use caching_rust_util_testing::test_utils::check_equality_groups;

#[derive(Debug)]
struct EqualByTens(i32);

impl PartialEq for EqualByTens {
    fn eq(&self, other: &Self) -> bool {
        self.0 / 10 == other.0 / 10
    }
}

impl Eq for EqualByTens {}

impl Hash for EqualByTens {
    fn hash<H: Hasher>(&self, state: &mut H) {
        (self.0 / 10).hash(state);
    }
}

#[test]
fn check_equality_groups_accepts_equivalence_classes() {
    // Test plan: check that check_equality_groups accepts equivalence classes. In this example,
    // we have two equivalence classes: [10, 11] and [20] for the EqualByTens type.
    let groups = vec![vec![EqualByTens(10), EqualByTens(11)], vec![EqualByTens(20)]];

    check_equality_groups(&groups);
}
