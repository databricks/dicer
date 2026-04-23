package com.databricks.dicer.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.fp;
import static com.databricks.dicer.external.javaapi.TestSliceUtils.highSliceKeyFromProto;
import static com.databricks.dicer.external.javaapi.TestSliceUtils.identity;
import static org.assertj.core.api.Assertions.assertThat;

import com.databricks.caching.util.javaapi.TestUtils;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP.HighSliceKeyP;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SliceTest {

  /** Some sample Slices in increasing order. */
  private static final List<Slice> ORDERED_SLICES = buildOrderedSlices();

  /** Builds a sequence of slices in increasing order from test data. */
  private static List<Slice> buildOrderedSlices() {
    SliceKeyTestDataP.Builder builder = SliceKeyTestDataP.newBuilder();
    try {
      TestUtils.loadTestData("dicer/common/test/data/slice_key_test_data.textproto", builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    SliceKeyTestDataP testData = builder.build();

    List<HighSliceKey> orderedHighSliceKeys = new ArrayList<>();
    for (HighSliceKeyP highSliceKeyP : testData.getOrderedHighSliceKeysList()) {
      HighSliceKey highSliceKey = highSliceKeyFromProto(highSliceKeyP);
      orderedHighSliceKeys.add(highSliceKey);
    }

    List<Slice> orderedSlices = new ArrayList<>();
    for (int i = 0; i < orderedHighSliceKeys.size(); i++) {
      HighSliceKey low = orderedHighSliceKeys.get(i);
      for (int j = i + 1; j < orderedHighSliceKeys.size(); j++) {
        HighSliceKey high = orderedHighSliceKeys.get(j);
        orderedSlices.add(Slice.create(low.asFinite(), high));
      }
    }
    return orderedSlices;
  }

  @Test
  void testCompareTo() {
    // Test plan: Verify that the `compareTo` implementation correctly compares Slices using
    // test data loaded from textproto.
    TestUtils.checkComparisons(ORDERED_SLICES);
  }

  @Test
  void testEquality() {
    // Test plan: Define groups of equivalent Slices and verify that equals and hashCode work as
    // expected.
    SliceKey identityLow = identity(new byte[] {1, 1, 1});
    SliceKey identityLowHigh = identity(new byte[] {3, 1, 1});
    // fp("Fili") and fp("Kili") happen to preserve the same order as "Fili" and "Kili".
    SliceKey fpLow = fp("Fili");
    SliceKey fpHigh = fp("Kili");

    List<List<Slice>> groups =
        List.of(
            List.of(
                Slice.FULL,
                Slice.create(SliceKey.MIN, InfinitySliceKey.INSTANCE),
                Slice.atLeast(SliceKey.MIN)),
            List.of(
                Slice.create(identityLow, identityLowHigh),
                Slice.create(identityLow, identityLowHigh)),
            List.of(Slice.create(fpLow, fpHigh), Slice.create(fpLow, fpHigh)),
            List.of(Slice.atLeast(fpLow), Slice.create(fpLow, InfinitySliceKey.INSTANCE)));

    TestUtils.checkEquality(groups);
  }

  @Test
  void testContains() {
    // Test plan: Verify that the contains method correctly determines whether a slice contains a
    // given SliceKey or another Slice.
    SliceKey someSliceKey = identity(new byte[] {1, 2, 3});
    SliceKey smallerSliceKey = identity(new byte[] {0, 2, 3});
    SliceKey largerSliceKey = identity(new byte[] {2, 2, 3});
    Slice slice1 = Slice.create(SliceKey.MIN, someSliceKey);
    Slice slice2 = Slice.atLeast(someSliceKey);
    Slice slice3 = Slice.FULL;

    assertThat(slice1.contains(SliceKey.MIN)).isTrue();
    assertThat(slice2.contains(someSliceKey)).isTrue();
    assertThat(slice3.contains(SliceKey.MIN)).isTrue();
    assertThat(slice1.contains(largerSliceKey)).isFalse();
    assertThat(slice2.contains(smallerSliceKey)).isFalse();

    assertThat(slice3.contains(slice2)).isTrue();
    assertThat(slice3.contains(slice1)).isTrue();
    assertThat(slice2.contains(slice1)).isFalse();
  }

  @Test
  void testGetters() {
    // Test plan: Verify that lowInclusive() and highExclusive() return the correct bounds that
    // were used to construct the Slice.
    SliceKey low = identity(new byte[] {1, 2, 3});
    SliceKey high = identity(new byte[] {4, 5, 6});
    Slice slice = Slice.create(low, high);

    assertThat(slice.lowInclusive()).isEqualTo(low);
    assertThat(slice.highExclusive()).isEqualTo(high);

    // Test with MIN and InfinitySliceKey
    assertThat(Slice.FULL.lowInclusive()).isEqualTo(SliceKey.MIN);
    assertThat(Slice.FULL.highExclusive()).isEqualTo(InfinitySliceKey.INSTANCE);

    // Test with atLeast factory method
    SliceKey atLeastKey = fp("low");
    Slice atLeastSlice = Slice.atLeast(atLeastKey);
    assertThat(atLeastSlice.lowInclusive()).isEqualTo(atLeastKey);
    assertThat(atLeastSlice.highExclusive()).isEqualTo(InfinitySliceKey.INSTANCE);
  }
}
