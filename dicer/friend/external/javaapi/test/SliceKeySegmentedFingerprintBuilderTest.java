package com.databricks.dicer.friend.external.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.dicer.external.javaapi.SliceKey;
import com.databricks.testing.DatabricksJavaTest;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SliceKeySegmentedFingerprintBuilderTest extends DatabricksJavaTest {

  @Test
  void fingerprintsEachSegmentInOrder() {
    // Test plan: Verify that the key is the concatenation of each segment's fingerprint, including
    // a trailing empty segment. Do this by building a three-segment key with every put overload
    // and comparing it with single-segment keys built from the same values.
    SliceKey key =
        SliceKeySegmentedFingerprintBuilder.create()
            .putString("workspace")
            .putLong(123L)
            .nextFingerprint()
            .putBytes(ByteString.copyFromUtf8("table"))
            .putBytes(ByteBuffer.wrap(new byte[] {1, 2}))
            .putBytes(new byte[] {3, 4})
            .nextFingerprint()
            .build();
    SliceKey first = SliceKey.newFingerprintBuilder().putString("workspace").putLong(123L).build();
    SliceKey second =
        SliceKey.newFingerprintBuilder()
            .putString("table")
            .putBytes(new byte[] {1, 2, 3, 4})
            .build();
    SliceKey empty = SliceKey.newFingerprintBuilder().build();
    assertThat(key.toRawBytes())
        .isEqualTo(first.toRawBytes().concat(second.toRawBytes()).concat(empty.toRawBytes()));
  }

  @Test
  void buildsEightSegments() {
    // Test plan: Verify that the maximum number of segments produces a 64-byte key that is the
    // concatenation of each segment's fingerprint. Do this by putting different values into every
    // segment and comparing the key with single-segment keys built from the same values.
    SliceKeySegmentedFingerprintBuilder builder = SliceKeySegmentedFingerprintBuilder.create();
    ByteString expected = ByteString.EMPTY;
    for (int i = 0; i < 8; i++) {
      if (i > 0) {
        builder.nextFingerprint();
      }
      builder.putString("segment" + i).putLong((long) i);
      SliceKey segmentKey =
          SliceKey.newFingerprintBuilder().putString("segment" + i).putLong((long) i).build();
      expected = expected.concat(segmentKey.toRawBytes());
    }
    SliceKey key = builder.build();
    assertThat(key.toRawBytes()).isEqualTo(expected);
    assertThat(key.toRawBytes().size()).isEqualTo(64);
  }

  @Test
  void buildLeavesTheBuilderUsable() {
    // Test plan: Verify that `build()` does not modify the builder. Do this by building the same
    // builder repeatedly, then putting more values into its open segment and starting another
    // segment, and comparing each key with one built by a fresh builder from the same values.
    SliceKeySegmentedFingerprintBuilder builder =
        SliceKeySegmentedFingerprintBuilder.create()
            .putString("first")
            .nextFingerprint()
            .putLong(1L);
    SliceKey key = builder.build();
    assertThat(builder.build()).isEqualTo(key);

    builder.putLong(2L);
    SliceKey keyAfterPut =
        SliceKeySegmentedFingerprintBuilder.create()
            .putString("first")
            .nextFingerprint()
            .putLong(1L)
            .putLong(2L)
            .build();
    assertThat(builder.build()).isEqualTo(keyAfterPut);

    builder.nextFingerprint().putString("third");
    SliceKey keyAfterNextFingerprint =
        SliceKeySegmentedFingerprintBuilder.create()
            .putString("first")
            .nextFingerprint()
            .putLong(1L)
            .putLong(2L)
            .nextFingerprint()
            .putString("third")
            .build();
    assertThat(builder.build()).isEqualTo(keyAfterNextFingerprint);
  }

  @Test
  void buildThrowsForMoreThanEightSegments() {
    // Test plan: Verify that `build()` throws once a ninth segment is started, including after a
    // put and with a tenth segment, and that `nextFingerprint()` itself does not throw.
    SliceKeySegmentedFingerprintBuilder builder = SliceKeySegmentedFingerprintBuilder.create();
    for (int i = 0; i < 8; i++) {
      builder.nextFingerprint();
    }
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("must not exceed 8 segments, got 9");
    builder.putLong(9L);
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("must not exceed 8 segments, got 9");
    builder.nextFingerprint();
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("must not exceed 8 segments, got 10");
  }
}
