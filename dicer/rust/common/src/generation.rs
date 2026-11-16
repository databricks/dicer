use std::fmt::Debug;
use std::time::{Duration, SystemTime};

use tonic::Status;
use caching_rust_util::PrettySystemTime;
use dicer_common_proto_common_proto::dicer::GenerationP;

/// The generation is used as a version number. It has two parts to it - an
/// `Incarnation` and a generation number. One should think of this as a 128-bit
/// integer with the incarnation number comprising the most significant bits.
/// The incarnation number is present to allow generations to be monotonically
/// increasing across multiple incarnations of the versioned object within and
/// across storage layers (see [`Incarnation`]).
///
/// In general a (durable) store can achieve monotonicity using a simple per-
/// object 64-bit generation number that is advanced on every update in the
/// store (expected to track time in milliseconds since the Unix epoch to serve
/// as a debugging aid, subject to monotonicity constraints). To maintain
/// monotonicity in the case that objects can potentially be deleted and re-
/// created (e.g. due to garbage collecting unused objects), the store can
/// additionally maintain a high watermark for used versions and require that
/// writes for new keys propose versions greater than this watermark.
///
/// No matter how a particular storage layer decides to achieve monotonicity for
/// generations, to maintain monotonicity across whole storage layer changes
/// (e.g. due to loss or corruption), the store incarnation must be bumped (see
/// [`Incarnation`]), making all future generations chosen by the Assigner to be
/// strictly greater than all previous generations. Bumping the store
/// incarnation is expected to be an extremely rare occurrence.
///
/// Note that the above discussion applies to durable stores which are able to
/// guarantee monotonicity. Non-durable stores in general do not have such
/// versioning guarantees, and monotonicity is best-effort. Whether guaranteed
/// monotonicity is supported (and perhaps other strong semantics that are
/// specific to the type of versioned object) is encoded in the `Incarnation`.
/// See `Incarnation` for details.
///
/// # Requirements
///
///  - `number` is non-negative.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct Generation {
    incarnation: Incarnation,
    number: UnixTimeVersion,
}

impl Generation {
    /// The empty generation that sorts before any other.
    pub const EMPTY: Generation = Generation::new(Incarnation::MIN, UnixTimeVersion::MIN);

    pub const fn new(incarnation: Incarnation, number: UnixTimeVersion) -> Generation {
        Generation {
            incarnation,
            number,
        }
    }

    /// The incarnation number corresponding to this generation.
    pub fn incarnation(&self) -> Incarnation {
        self.incarnation
    }

    /// The generation number corresponding to this generation.
    pub fn number(&self) -> UnixTimeVersion {
        self.number
    }

    pub fn from_proto(proto: &GenerationP) -> Result<Generation, Status> {
        let incarnation = Incarnation::new(proto.incarnation.unwrap_or(0))?;
        let number = UnixTimeVersion::from_unix_millis(proto.number.unwrap_or(0))?;
        Ok(Generation::new(incarnation, number))
    }

    pub fn to_proto(&self) -> GenerationP {
        GenerationP {
            incarnation: Some(self.incarnation.value),
            number: Some(self.number.unix_millis),
        }
    }
}

impl Debug for Generation {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}#{:?}", self.incarnation, self.number)
    }
}

/// Represents the incarnation number of a versioned object (see
/// [`Generation`]).
///
/// Incarnations form the upper-64 bits of the 128-bit Generation. The storage
/// layer only increases the lower-64 bits of the Generation when creating new
/// versioned objects, which then makes it possible to create greater
/// Generations than ever before by simply increasing the Incarnation.
///
/// An incarnation number encodes whether or not the set of all versioned
/// objects within that incarnation form a linear history and whether certain
/// application-specific optimizations or strong semantics are supported (see
/// [`is_strict`] for definition). Incarnations encoding these strong
/// semantics for their versioned objects are called "strict" incarnations, and
/// otherwise are called "loose" incarnations.
///
/// Non-durable stores which are unable to reliably track object incarnations
/// should choose a fixed number (an odd number or 0, to form a "loose"
/// incarnation, see [`is_strict`]) for all chosen object incarnations. Durable
/// stores, on the other hand, are expected (but not required) to utilize strict
/// incarnations and provide linear histories of their versioned objects.
///
/// # Requirements
///
///  - `value` is non-negative and less than `5 << 48`.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct Incarnation {
    value: i64,
}

impl Incarnation {
    /// The minimum valid incarnation value.
    ///
    /// ```
    /// // Demonstrate that this constant is valid.
    /// use dicer_rust_common::Incarnation;
    /// assert_eq!(Incarnation::new(0).unwrap(), Incarnation::MIN);
    /// ```
    pub const MIN: Incarnation = Incarnation { value: 0 };

    /// The maximum valid incarnation value.
    ///
    /// ```
    /// // Demonstrate that this constant is valid.
    /// use dicer_rust_common::Incarnation;
    /// assert_eq!(Incarnation::new((5i64 << 48) - 1).unwrap(), Incarnation::MAX);
    /// ```
    pub const MAX: Incarnation = Incarnation {
        value: Self::UPPER_BOUND_FAT_FINGER_GUARDRAIL - 1,
    };

    pub fn new(value: i64) -> Result<Incarnation, Status> {
        if value < Self::MIN.value() {
            Err(Status::invalid_argument("Incarnation number must be non-negative"))
        } else if value >= Self::UPPER_BOUND_FAT_FINGER_GUARDRAIL {
            Err(Status::invalid_argument(format!(
                "Value is too large ({} >= (5 << 48)). Was this intentional?",
                value
            )))
        } else {
            Ok(Incarnation { value })
        }
    }

    /// The incarnation value, which is guaranteed to be non-negative.
    /// Represented as `i64` rather than `u64` for compatibility with
    /// Java/Scala client libraries.
    pub fn value(&self) -> i64 {
        self.value
    }

    /// There are two types of Incarnations, called "loose" and "strict". When
    /// "loose" and "strict" are used in the context of generations, the terms
    /// mean that the generation's incarnation is loose or strict.
    ///
    /// Strict generations are used by durable stores and guarantee linear (non-
    /// divergent) histories of their versioned objects. That is, if an object
    /// has generations g1 and g2, g1.incarnation() == g2.incarnation(), and
    /// g1 < g2, then the object at generation g2 is guaranteed to have been
    /// created causally after the object at generation g1. Strict generations
    /// are also used by individual stores to express or implement support for
    /// certain optimizations or even additional semantic guarantees on their
    /// versioned objects which are application specific. For example,
    /// assignments in strict incarnations support diffs in order to efficiently
    /// sync assignment state among system components, and additionally make
    /// strong guarantees about continuous assignment of slices. See
    /// `SliceAssignment` for details.
    ///
    /// Loose generations are used by non-durable stores and do not have the
    /// above guarantee. That is, for a given object, the set of all generations
    /// within the same loose incarnation may form a divergent history. For
    /// example, an object in a loose incarnation may have generation numbers
    /// 8, 9, and 10, but 10 may have been generated causally after 8 but in
    /// parallel with 9, and so not be aware of 9's existence. There may even be
    /// multiple versions with generation number 9. In general, anything is
    /// possible.
    ///
    /// "Strict" is encoded as a positive even value. Otherwise, the incarnation
    /// is "loose". This interleaving of loose and strict incarnations allows us
    /// to always be able to move "ahead" in the versioning space to either
    /// loose or strict incarnations as needed (e.g., if a durable store went
    /// kaput and we needed to move ahead to a non-durable store in an
    /// emergency).
    pub fn is_strict(&self) -> bool {
        self.value != 0 && (self.value & 1) == 0
    }

    /// Returns the opposite
    pub fn is_loose(&self) -> bool {
        !self.is_strict()
    }

    /// The maximum valid incarnation that prevents us from accidentally
    /// exhausting the Incarnation space, as the incarnation value can never go
    /// backwards. The value itself does not represent any sort of logical
    /// requirement of the system.
    ///
    /// The reason for this bound being so large is that "store incarnations"
    /// originally formed the upper 16 bits of the whole 64 bit incarnation,
    /// while `entity incarnations` formed the lower 48 bits. Some mt-shards had
    /// a value of 4 for their store incarnation while using the in-memory
    /// store, so to ensure that we produce larger generations than those, we
    /// must use an Incarnation at least as large as was ever used in those
    /// shards, which is 4 << 48 + 1.
    const UPPER_BOUND_FAT_FINGER_GUARDRAIL: i64 = 5i64 << 48;
}

impl Debug for Incarnation {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.value.fmt(f)
    }
}

/// A version number that may be interpreted as the number of milliseconds since
/// the Unix epoch.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct UnixTimeVersion {
    unix_millis: i64,
}

impl UnixTimeVersion {
    /// The minimum valid time version, set to the Unix epoch.
    ///
    /// ```
    /// // Demonstrate that this constant is valid.
    /// use dicer_rust_common::UnixTimeVersion;
    /// assert_eq!(UnixTimeVersion::from_unix_millis(0).unwrap(), UnixTimeVersion::MIN);
    /// ```
    pub const MIN: UnixTimeVersion = UnixTimeVersion { unix_millis: 0 };

    /// Creates an instance of `UnixTimeVersion` representing a version
    /// `unix_millis` after the unix epoch. Fails with an invalid argument
    /// error if the timestamp is negative.
    pub fn from_unix_millis(unix_millis: i64) -> Result<UnixTimeVersion, Status> {
        if unix_millis < Self::MIN.unix_millis {
            Err(Status::invalid_argument("Unix time version must be non-negative"))
        } else {
            Ok(UnixTimeVersion { unix_millis })
        }
    }

    /// Returns the number of milliseconds between the Unix epoch and this `UnixTimeVersion`.
    pub fn to_unix_millis(self) -> i64 {
        self.unix_millis
    }

    pub fn to_time(self) -> SystemTime {
        // Since `unix_millis` is non-negative (guaranteed by `from_unix_millis()`), we can safely
        // convert to u64.
        let unix_millis = self.unix_millis as u64;
        SystemTime::UNIX_EPOCH + Duration::from_millis(unix_millis)
    }
}

impl Debug for UnixTimeVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({:?})", self.unix_millis, PrettySystemTime(self.to_time()))
    }
}
