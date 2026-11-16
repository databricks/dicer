/// Benchmark that measures SliceKey creation performance mirroring the Scala version
/// of SliceKeyCreationBenchmark.
///
/// Results on 2025-11-11:
///  
/// Baseline that doesn't report SliceKey size histogram:
/// {{{
/// $ bazel run //dicer/rust/external/types:slice_key_creation_benchmark -- --bench
///
/// create_slice_keys       time:   [49.111 ms 49.366 ms 49.730 ms]
///                         change: [+0.6250% +1.1607% +1.9112%] (p = 0.00 < 0.05)
///                         Change within noise threshold.
/// }}}
///
/// Results with SliceKeys reporting size histogram upon creation:
/// {{{
/// $ bazel run //dicer/rust/external/types:slice_key_creation_benchmark -- --bench
///
/// create_slice_keys       time:   [59.163 ms 59.618 ms 60.278 ms]
///                         change: [+19.501% +20.768% +22.335%] (p = 0.00 < 0.05)
///                         Performance has regressed.
/// }}}
///
use criterion::{black_box, criterion_group, criterion_main, Bencher, Criterion};
use rand::Rng;
use dicer_rust_external_types::SliceKey;

// The number of SliceKeys to create in batch during each benchmark iteration. (See
// `create_slice_keys`.) In each benchmark iteration, we create a batch of SliceKeys to
// make the measurement more stable.
const SLICE_KEY_BATCH_SIZE: usize = 100_000;

/// Appends the function to be benchmarked to the `criterion` instance: The test
/// function will create `SLICE_KEY_BATCH_SIZE` slice keys using fingerprint64
/// from 8 random bytes in each benchmark iteration.
fn create_slice_keys(criterion: &mut Criterion) {
    // Pre-compute a vector of bytes to be supplied to the new_farmhash_fingerprint64
    // function to avoid the overhead of creating the bytes in each iteration.
    let bytes_vec: Vec<[u8; 8]> = (0..1000)
        .map(|_: usize| rand::rng().random::<u64>().to_be_bytes())
        .collect();
    criterion.bench_function("create_slice_keys", |bencher: &mut Bencher| {
        bencher.iter(|| {
            for _ in 0..SLICE_KEY_BATCH_SIZE {
                // Randomly pick a byte array from the pre-calculated vector.
                let random_index: usize = rand::rng().random_range(0..bytes_vec.len());
                let bytes: &[u8; 8] = &bytes_vec[random_index];
                let slice_key = SliceKey::new_farmhash_fingerprint64(bytes);
                black_box(slice_key);
            }
        })
    });
}

criterion_group!(benches, create_slice_keys);

criterion_main!(benches);
