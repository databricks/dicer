use std::collections::BTreeMap;

use tonic::Status;

/// Maps caller-supplied physical nodes and lookup keys to their byte representations. Used to
/// compute the 64-bit ring positions of each.
pub trait TypeMapper: Send + Sync {
    /// The physical node type placed on the ring.
    type Node;

    /// The lookup key type.
    type Key;

    /// Maps a physical node to a byte representation borrowed from the node. Equal nodes must map to
    /// equal bytes.
    fn map_node<'a>(&self, node: &'a Self::Node) -> &'a [u8];

    /// Maps a lookup key to a byte representation borrowed from the key. Equal keys must map to
    /// equal bytes.
    fn map_key<'a>(&self, key: &'a Self::Key) -> &'a [u8];
}

/// Immutable consistent hash ring mapping lookup keys to physical nodes, using the [`TypeMapper`]
/// `M` to derive the bytes hashed for each node and key. The node and key types are `M::Node` and
/// `M::Key`.
///
/// The ring distinguishes between physical nodes (the `M::Node` values that the caller supplies)
/// and virtual nodes (the `vnodes_per_node` ring positions occupied by each physical node). These
/// virtual nodes are created to balance key ownership even with a small number of physical nodes,
/// with a high `vnodes_per_node` value improving distribution uniformity at the cost of more
/// entries in the ring. A lookup hashes the key's byte representation, finds the smallest ring
/// position greater than or equal to that hash, and returns the physical node that owns that
/// position. If no such position exists, the lookup wraps to the smallest entry.
///
/// If two virtual nodes hash to the same ring position, the one whose physical node comes later
/// in `nodes` wins. The ring therefore depends on the order of `nodes`, so callers that need
/// independent processes to build identical rings must order `nodes` deterministically everywhere.
///
/// Guaranteed to produce the same key-to-node assignments as the Scala implementation when
/// `TypeMapper` yields identical byte sequences for the same logical nodes and keys.
///
/// `ConsistentHashRing` is `Send + Sync` whenever `M::Node` is `Send + Sync`.
pub struct ConsistentHashRing<M: TypeMapper> {
    /// The physical nodes placed on the ring, referenced by index from `ring`.
    nodes: Vec<M::Node>,
    /// Maps physical nodes and lookup keys to the byte representations the ring hashes.
    type_mapper: M,
    /// The 64-bit ring positions and insertion sequence numbers mapped to the index (into `nodes`)
    /// of the owning physical node. Each physical node appears `vnodes_per_node` times.
    ///
    /// Ring hashes are signed to match the Scala `Long` ordering.
    ring: BTreeMap<(i64, usize), usize>,
}

impl<M: TypeMapper> ConsistentHashRing<M> {
    /// Creates a ring with the given nodes and `vnodes_per_node` positions per node.
    ///
    /// Returns an `INVALID_ARGUMENT` status if `nodes` is empty or `vnodes_per_node` is not
    /// positive.
    pub fn new(
        nodes: Vec<M::Node>,
        vnodes_per_node: i32,
        type_mapper: M,
    ) -> Result<ConsistentHashRing<M>, Status> {
        if nodes.is_empty() {
            return Err(Status::invalid_argument("nodes must not be empty"));
        }
        if vnodes_per_node <= 0 {
            return Err(Status::invalid_argument(format!(
                "vnodes_per_node must be > 0, got {vnodes_per_node}"
            )));
        }

        // Place each physical node at `vnodes_per_node` positions on the ring, keyed by the node's
        // index in `nodes` so the ring never holds a separate copy of the node.
        let mut ring: BTreeMap<(i64, usize), usize> = BTreeMap::new();
        // To preserve last-writer-wins semantics while traversing toward the first vnode at or above
        // a key's hash, count down from the integer maximum. A lookup from `(hash, usize::MIN)` then
        // encounters the most recently inserted vnode first when positions collide.
        let mut insertion_sequencer: usize = usize::MAX;
        for (node_index, node) in nodes.iter().enumerate() {
            let node_bytes: &[u8] = type_mapper.map_node(node);
            for vnode_index in 1..=vnodes_per_node {
                // Append the 4-byte big-endian vnode_index to the node bytes to generate unique
                // vnode byte sequences.
                let mut buffer: Vec<u8> = node_bytes.to_vec();
                buffer.extend_from_slice(&vnode_index.to_be_bytes());
                ring.insert((hash_bytes(&buffer), insertion_sequencer), node_index);
                insertion_sequencer = insertion_sequencer.wrapping_sub(1);
            }
        }

        Ok(ConsistentHashRing {
            nodes,
            type_mapper,
            ring,
        })
    }

    /// Returns an iterator over the physical nodes placed on the ring.
    pub fn nodes(&self) -> impl Iterator<Item = &M::Node> {
        self.nodes.iter()
    }

    /// Returns the node responsible for `key`.
    pub fn lookup(&self, key: &M::Key) -> &M::Node {
        let hashed_value: i64 = hash_bytes(self.type_mapper.map_key(key));
        // Find the smallest position >= the hashed value, wrapping to the first entry if none
        // exists.
        let (_, node_index): (&(i64, usize), &usize) = self
            .ring
            .range((hashed_value, usize::MIN)..)
            .next()
            .or_else(|| self.ring.iter().next())
            .expect("ring is non-empty by construction");
        &self.nodes[*node_index]
    }

    /// Returns an iterator over the ring's nodes in ring order, starting from the node responsible
    /// for `key`. Each subsequent element is the next node encountered while walking the ring
    /// clockwise and wrapping around back to the node responsible for `key`. The iterator visits
    /// each node `vnodes_per_node` times.
    pub fn iterator_from(&self, key: &M::Key) -> impl Iterator<Item = &M::Node> {
        let hashed_value: i64 = hash_bytes(self.type_mapper.map_key(key));
        // The position owning `key`, which is the smallest position >= the hashed value, wrapping
        // to the first position if none exists. The ring is always non-empty, so `iterator_from`
        // can rely on at least one entry being present.
        let (&key_owner_position, _): (&(i64, usize), &usize) = self
            .ring
            .range((hashed_value, usize::MIN)..)
            .next()
            .or_else(|| self.ring.iter().next())
            .expect("ring is non-empty by construction");
        // Walk clockwise from the key owner to the end of the ring, then wrap from the ring's start
        // up to (but not including) the key owner, yielding every vnode entry once in ring order.
        self.ring
            .range(key_owner_position..)
            .chain(self.ring.range(..key_owner_position))
            .map(move |(_, node_index): (&(i64, usize), &usize)| &self.nodes[*node_index])
    }
}

/// Hashes `buffer` to a 64-bit ring position using FarmHash fingerprint64, whose bits are
/// reinterpreted as a signed `i64` to match the signed ordering of the Scala ring implementation.
fn hash_bytes(buffer: &[u8]) -> i64 {
    farmhash::fingerprint64(buffer).cast_signed()
}
