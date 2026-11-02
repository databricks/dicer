use std::cmp::max;

use tonic::Status;
use caching_util_proto_hyper_log_log_proto::databricks::caching::util::HyperLogLogP;

/// HyperLogLog is used for efficient estimation of cardinality. It behaves like an add-only set.
///
/// The original paper is https://dmtcs.episciences.org/3545/pdf. A later paper describes the
/// algorithm and several extensions (which they call HyperLogLog++) in
/// https://static.googleusercontent.com/media/research.google.com/en/pubs/archive/40671.pdf. We do
/// not use the extensions from the latter paper, but its descriptions are easier to follow.
#[derive(Clone, Debug, PartialEq)]
pub struct HyperLogLog {
    // HyperLogLog works off of a simple idea. We can estimate the cardinality of a stream by
    // measuring the "rarest" item that we've seen so far, under the assumption that we probably
    // need to see a lot of values in order to see a rare one.
    //
    // The way we measure rarity is with the number of leading zeros in the hash of the items. For
    // a good hash function that uniformly fills the output space, a lot of leading zeros in the
    // hash should be rare.
    //
    // Just doing this estimation would be vulnerable to getting unlucky, since we might have only
    // seen one value but it just happened to be a rare variety. To combat that, we use some of the
    // low-order bits of the hash to bucket the items, the register for that bucket keeps track of
    // the largest number of leading zeros we've seen for that bucket.
    //
    // Then, we use a harmonic mean over the estimates for each of the registers, which averages
    // away the outliers we get by being "unlucky" and seeing a rare value sooner than the
    // statistics would predict.
    //
    // Note: The hashes are 64 bits and we're storing the number of leading zeros, so we only ever
    // use 6 bits for each of these and we could save 25% by packing these more tightly. Since
    // we're in practice never estimating extremely large cardinalities, we also wouldn't lose much
    // by only using only 4 bits per register. 256 bytes is already reasonably cheap so we haven't
    // bothered with that optimization.
    registers: [u8; Self::M],
}

impl HyperLogLog {
    // Referred to as 'precision' in the paper. Determines both the relative error expected in
    // estimates and the space usage of the structure.
    //
    // Kept as a constant rather than a parameter just to avoid implementing the downsampling method
    // required to merge HyperLogLogs of different precisions.
    const P: usize = 8;

    // We use M bytes of space, and get 1.04/sqrt(M) relative error. That is, for our choice here, we
    // get 1.04/sqrt(256) = 6.5% expected error.
    const M: usize = 1 << Self::P;
    const M_F64: f64 = Self::M as f64;

    // HyperLogLog vastly overestimates small cardinalities. For small values, we use LinearCounting.
    //
    // Descriptions of LinearCounting have a set of bits, hashing each added value to one of the bits
    // and setting it to 1, then estimate the cardinality by doing some math over the number of
    // zeros remaining.
    //
    // The registers we have for HyperLogLog have this same information, though they're a whole byte
    // instead of a bit each. Any value that gets hashed into the register will set it to something
    // non-zero.
    const LINEAR_COUNTING_THRESHOLD: f64 = 5.0 * Self::M_F64 / 2.0;

    // There is a larger formula with an integral in the paper, but Zetasketch approximates it this
    // way for values of P > 6.
    const ALPHA: f64 = 0.7213 / (1.0 + 1.079 / Self::M_F64);

    pub fn new() -> Self {
        Self {
            registers: [0u8; Self::M],
        }
    }

    /// Adds the given key to the HyperLogLog.
    pub fn add(&mut self, key: &[u8]) {
        let hash: u64 = farmhash::fingerprint64(key);
        let register_idx: usize = (hash % (Self::M as u64)) as usize;

        // We use the p low-order bits to select the register so they do not carry any more
        // information, don't count them as leading zeroes by setting them all to 1.
        let lower_ones = (Self::M - 1) as u64;
        let leading_zeros = (hash | lower_ones).leading_zeros();

        // The +1 here allows us to the tell the difference between "no observations" for a bucket
        // and "an observation but it had no leading zeros", the rest of the math is designed
        // around this also.
        self.registers[register_idx] = max(self.registers[register_idx], (leading_zeros + 1) as u8);
    }

    /// Returns an estimate for the number of *unique* keys that were given to [`add()`].
    pub fn estimate(&self) -> u64 {
        let num_zeros: usize = self.registers.iter().filter(|&&x| x == 0).count();

        // Implies no calls to add().
        if num_zeros == Self::M {
            return 0;
        }

        if num_zeros > 0 {
            // LinearCounting for low cardinalities.
            let estimate: f64 = Self::M_F64 * f64::ln(Self::M_F64 / (num_zeros as f64));
            if estimate < Self::LINEAR_COUNTING_THRESHOLD {
                return estimate.round() as u64;
            }
        }

        // Harmonic mean for high cardinalities.
        //
        // Each of the registers is recording the leading zeros in the hashes of the keys
        // routed to it, so 2^leading_zeros would be an estimate for how many keys arrived at
        // that register.
        //
        // We take the harmonic mean over the registers in order to discount outliers. The harmonic
        // mean is n / (sum(1/x_i)), where x_i is the estimate for register i.
        let harmonic_mean: f64 = Self::M_F64
            / self
                .registers
                .iter()
                // This shift can't overflow because the register value is capped at 64-p+1.
                .map(|&x| 1.0 / ((1u64 << x) as f64))
                .sum::<f64>();
        // The harmonic mean is an estimate for the number of keys that arrived at each register.
        // Since there are M registers, we multiply by M here to get an estimate for the total.
        //
        // ALPHA is a correction for hash collisions, we expect the whole algorithm to undercount
        // because sometimes distinct keys will affect the registers in exactly the same way and we
        // can't tell the difference.
        let estimate: f64 = Self::ALPHA * Self::M_F64 * harmonic_mean;
        estimate.round() as u64
    }

    /// Merges the observations in [`other`] into [`self`]. That is, afterwards, [`estimate()`]
    /// will return the total estimated cardinality of values added to either [`self`] or
    /// [`other`].
    pub fn merge_from(&mut self, other: &HyperLogLog) {
        for i in 0..Self::M {
            self.registers[i] = max(self.registers[i], other.registers[i])
        }
    }

    pub fn to_proto(&self) -> HyperLogLogP {
        HyperLogLogP {
            inner: Some(self.registers.to_vec()),
        }
    }

    pub fn from_proto(pb: &HyperLogLogP) -> Result<Self, Status> {
        let registers_vec = pb
            .inner
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("missing HyperLogLogP.inner"))?;

        for &register in registers_vec {
            if register > (64 - Self::P + 1) as u8 {
                return Err(Status::invalid_argument(format!(
                    "nonsense register value {}, must be in [0, {}]",
                    register,
                    64 - Self::P + 1,
                )));
            }
        }

        Ok(Self {
            registers: registers_vec[..].try_into().map_err(|_| {
                Status::invalid_argument(format!(
                    "cannot deserialize HyperLogLog: wrong length: {} != {}",
                    registers_vec.len(),
                    Self::M,
                ))
            })?,
        })
    }
}

impl Default for HyperLogLog {
    fn default() -> Self {
        Self::new()
    }
}
