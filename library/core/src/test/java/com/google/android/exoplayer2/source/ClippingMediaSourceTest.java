/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.os.Handler;
import android.support.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Timeline.Period;
import com.google.android.exoplayer2.Timeline.Window;
import com.google.android.exoplayer2.source.ClippingMediaSource.IllegalClippingException;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.MediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.google.android.exoplayer2.testutil.FakeMediaPeriod;
import com.google.android.exoplayer2.testutil.FakeMediaSource;
import com.google.android.exoplayer2.testutil.FakeTimeline;
import com.google.android.exoplayer2.testutil.FakeTimeline.TimelineWindowDefinition;
import com.google.android.exoplayer2.testutil.MediaSourceTestRunner;
import com.google.android.exoplayer2.testutil.RobolectricUtil;
import com.google.android.exoplayer2.testutil.TimelineAsserts;
import com.google.android.exoplayer2.upstream.Allocator;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for {@link ClippingMediaSource}. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {RobolectricUtil.CustomLooper.class, RobolectricUtil.CustomMessageQueue.class})
public final class ClippingMediaSourceTest {

  private static final long TEST_PERIOD_DURATION_US = 1000000;
  private static final long TEST_CLIP_AMOUNT_US = 300000;

  private Window window;
  private Period period;

  @Before
  public void setUp() throws Exception {
    window = new Timeline.Window();
    period = new Timeline.Period();
  }

  @Test
  public void testNoClipping() throws IOException {
    Timeline timeline = new SinglePeriodTimeline(C.msToUs(TEST_PERIOD_DURATION_US), true, false);

    Timeline clippedTimeline = getClippedTimeline(timeline, 0, TEST_PERIOD_DURATION_US);

    assertThat(clippedTimeline.getWindowCount()).isEqualTo(1);
    assertThat(clippedTimeline.getPeriodCount()).isEqualTo(1);
    assertThat(clippedTimeline.getWindow(0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US);
    assertThat(clippedTimeline.getPeriod(0, period).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US);
  }

  @Test
  public void testClippingUnseekableWindowThrows() throws IOException {
    Timeline timeline = new SinglePeriodTimeline(C.msToUs(TEST_PERIOD_DURATION_US), false, false);

    // If the unseekable window isn't clipped, clipping succeeds.
    getClippedTimeline(timeline, 0, TEST_PERIOD_DURATION_US);
    try {
      // If the unseekable window is clipped, clipping fails.
      getClippedTimeline(timeline, 1, TEST_PERIOD_DURATION_US);
      fail("Expected clipping to fail.");
    } catch (IllegalClippingException e) {
      assertThat(e.reason).isEqualTo(IllegalClippingException.REASON_NOT_SEEKABLE_TO_START);
    }
  }

  @Test
  public void testClippingStart() throws IOException {
    Timeline timeline = new SinglePeriodTimeline(C.msToUs(TEST_PERIOD_DURATION_US), true, false);

    Timeline clippedTimeline =
        getClippedTimeline(timeline, TEST_CLIP_AMOUNT_US, TEST_PERIOD_DURATION_US);
    assertThat(clippedTimeline.getWindow(0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
    assertThat(clippedTimeline.getPeriod(0, period).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US);
  }

  @Test
  public void testClippingEnd() throws IOException {
    Timeline timeline = new SinglePeriodTimeline(C.msToUs(TEST_PERIOD_DURATION_US), true, false);

    Timeline clippedTimeline =
        getClippedTimeline(timeline, 0, TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
    assertThat(clippedTimeline.getWindow(0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
    assertThat(clippedTimeline.getPeriod(0, period).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
  }

  @Test
  public void testClippingStartAndEndInitial() throws IOException {
    // Timeline that's dynamic and not seekable. A child source might report such a timeline prior
    // to it having loaded sufficient data to establish its duration and seekability. Such timelines
    // should not result in clipping failure.
    Timeline timeline =
        new SinglePeriodTimeline(C.TIME_UNSET, /* isSeekable= */ false, /* isDynamic= */ true);

    Timeline clippedTimeline =
        getClippedTimeline(
            timeline, TEST_CLIP_AMOUNT_US, TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 2);
    assertThat(clippedTimeline.getWindow(0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 3);
    assertThat(clippedTimeline.getPeriod(0, period).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 2);
  }

  @Test
  public void testClippingToEndOfSourceWithDurationSetsDuration() throws IOException {
    // Create a child timeline that has a known duration.
    Timeline timeline =
        new SinglePeriodTimeline(
            /* durationUs= */ TEST_PERIOD_DURATION_US,
            /* isSeekable= */ true,
            /* isDynamic= */ false);

    // When clipping to the end, the clipped timeline should also have a duration.
    Timeline clippedTimeline =
        getClippedTimeline(timeline, TEST_CLIP_AMOUNT_US, C.TIME_END_OF_SOURCE);
    assertThat(clippedTimeline.getWindow(/* windowIndex= */ 0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
  }

  @Test
  public void testClippingToEndOfSourceWithUnsetDurationDoesNotSetDuration() throws IOException {
    // Create a child timeline that has an unknown duration.
    Timeline timeline =
        new SinglePeriodTimeline(
            /* durationUs= */ C.TIME_UNSET, /* isSeekable= */ true, /* isDynamic= */ false);

    // When clipping to the end, the clipped timeline should also have an unset duration.
    Timeline clippedTimeline =
        getClippedTimeline(timeline, TEST_CLIP_AMOUNT_US, C.TIME_END_OF_SOURCE);
    assertThat(clippedTimeline.getWindow(/* windowIndex= */ 0, window).getDurationUs())
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void testClippingStartAndEnd() throws IOException {
    Timeline timeline = new SinglePeriodTimeline(C.msToUs(TEST_PERIOD_DURATION_US), true, false);

    Timeline clippedTimeline =
        getClippedTimeline(
            timeline, TEST_CLIP_AMOUNT_US, TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 2);
    assertThat(clippedTimeline.getWindow(0, window).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 3);
    assertThat(clippedTimeline.getPeriod(0, period).getDurationUs())
        .isEqualTo(TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US * 2);
  }

  @Test
  public void testWindowAndPeriodIndices() throws IOException {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(1, 111, true, false, TEST_PERIOD_DURATION_US));
    Timeline clippedTimeline =
        getClippedTimeline(
            timeline, TEST_CLIP_AMOUNT_US, TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US);
    TimelineAsserts.assertWindowIds(clippedTimeline, 111);
    TimelineAsserts.assertPeriodCounts(clippedTimeline, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        clippedTimeline, Player.REPEAT_MODE_OFF, false, C.INDEX_UNSET);
    TimelineAsserts.assertPreviousWindowIndices(clippedTimeline, Player.REPEAT_MODE_ONE, false, 0);
    TimelineAsserts.assertPreviousWindowIndices(clippedTimeline, Player.REPEAT_MODE_ALL, false, 0);
    TimelineAsserts.assertNextWindowIndices(
        clippedTimeline, Player.REPEAT_MODE_OFF, false, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(clippedTimeline, Player.REPEAT_MODE_ONE, false, 0);
    TimelineAsserts.assertNextWindowIndices(clippedTimeline, Player.REPEAT_MODE_ALL, false, 0);
  }

  @Test
  public void testEventTimeWithinClippedRange() throws IOException {
    MediaLoadData mediaLoadData =
        getClippingMediaSourceMediaLoadData(
            /* clippingStartUs= */ TEST_CLIP_AMOUNT_US,
            /* clippingEndUs= */ TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US,
            /* eventStartUs= */ TEST_CLIP_AMOUNT_US + 1000,
            /* eventEndUs= */ TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US - 1000);
    assertThat(C.msToUs(mediaLoadData.mediaStartTimeMs)).isEqualTo(1000);
    assertThat(C.msToUs(mediaLoadData.mediaEndTimeMs))
        .isEqualTo(TEST_PERIOD_DURATION_US - 2 * TEST_CLIP_AMOUNT_US - 1000);
  }

  @Test
  public void testEventTimeOutsideClippedRange() throws IOException {
    MediaLoadData mediaLoadData =
        getClippingMediaSourceMediaLoadData(
            /* clippingStartUs= */ TEST_CLIP_AMOUNT_US,
            /* clippingEndUs= */ TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US,
            /* eventStartUs= */ TEST_CLIP_AMOUNT_US - 1000,
            /* eventEndUs= */ TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US + 1000);
    assertThat(C.msToUs(mediaLoadData.mediaStartTimeMs)).isEqualTo(0);
    assertThat(C.msToUs(mediaLoadData.mediaEndTimeMs))
        .isEqualTo(TEST_PERIOD_DURATION_US - 2 * TEST_CLIP_AMOUNT_US);
  }

  @Test
  public void testUnsetEventTime() throws IOException {
    MediaLoadData mediaLoadData =
        getClippingMediaSourceMediaLoadData(
            /* clippingStartUs= */ TEST_CLIP_AMOUNT_US,
            /* clippingEndUs= */ TEST_PERIOD_DURATION_US - TEST_CLIP_AMOUNT_US,
            /* eventStartUs= */ C.TIME_UNSET,
            /* eventEndUs= */ C.TIME_UNSET);
    assertThat(C.msToUs(mediaLoadData.mediaStartTimeMs)).isEqualTo(C.TIME_UNSET);
    assertThat(C.msToUs(mediaLoadData.mediaEndTimeMs)).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void testEventTimeWithUnsetDuration() throws IOException {
    MediaLoadData mediaLoadData =
        getClippingMediaSourceMediaLoadData(
            /* clippingStartUs= */ TEST_CLIP_AMOUNT_US,
            /* clippingEndUs= */ C.TIME_END_OF_SOURCE,
            /* eventStartUs= */ TEST_CLIP_AMOUNT_US,
            /* eventEndUs= */ TEST_CLIP_AMOUNT_US + 1_000_000);
    assertThat(C.msToUs(mediaLoadData.mediaStartTimeMs)).isEqualTo(0);
    assertThat(C.msToUs(mediaLoadData.mediaEndTimeMs)).isEqualTo(1_000_000);
  }

  /**
   * Wraps a timeline of duration {@link #TEST_PERIOD_DURATION_US} in a {@link ClippingMediaSource},
   * sends a media source event from the child source and returns the reported {@link MediaLoadData}
   * for the clipping media source.
   *
   * @param clippingStartUs The start time of the media source clipping, in microseconds.
   * @param clippingEndUs The end time of the media source clipping, in microseconds.
   * @param eventStartUs The start time of the media source event (before clipping), in
   *     microseconds.
   * @param eventEndUs The end time of the media source event (before clipping), in microseconds.
   * @return The reported {@link MediaLoadData} for that event.
   */
  private static MediaLoadData getClippingMediaSourceMediaLoadData(
      long clippingStartUs, long clippingEndUs, final long eventStartUs, final long eventEndUs)
      throws IOException {
    FakeMediaSource fakeMediaSource =
        new FakeMediaSource(
            new SinglePeriodTimeline(
                TEST_PERIOD_DURATION_US, /* isSeekable= */ true, /* isDynamic= */ false),
            /* manifest= */ null) {
          @Override
          protected FakeMediaPeriod createFakeMediaPeriod(
              MediaPeriodId id,
              TrackGroupArray trackGroupArray,
              Allocator allocator,
              EventDispatcher eventDispatcher) {
            eventDispatcher.downstreamFormatChanged(
                new MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_UNKNOWN,
                    /* trackFormat= */ null,
                    C.SELECTION_REASON_UNKNOWN,
                    /* trackSelectionData= */ null,
                    C.usToMs(eventStartUs),
                    C.usToMs(eventEndUs)));
            return super.createFakeMediaPeriod(id, trackGroupArray, allocator, eventDispatcher);
          }
        };
    final ClippingMediaSource clippingMediaSource =
        new ClippingMediaSource(fakeMediaSource, clippingStartUs, clippingEndUs);
    MediaSourceTestRunner testRunner =
        new MediaSourceTestRunner(clippingMediaSource, /* allocator= */ null);
    final MediaLoadData[] reportedMediaLoadData = new MediaLoadData[1];
    try {
      testRunner.runOnPlaybackThread(
          new Runnable() {
            @Override
            public void run() {
              clippingMediaSource.addEventListener(
                  new Handler(),
                  new DefaultMediaSourceEventListener() {
                    @Override
                    public void onDownstreamFormatChanged(
                        int windowIndex,
                        @Nullable MediaPeriodId mediaPeriodId,
                        MediaLoadData mediaLoadData) {
                      reportedMediaLoadData[0] = mediaLoadData;
                    }
                  });
            }
          });
      testRunner.prepareSource();
      // Create period to send the test event configured above.
      testRunner.createPeriod(
          new MediaPeriodId(/* periodIndex= */ 0, /* windowSequenceNumber= */ 0));
      assertThat(reportedMediaLoadData[0]).isNotNull();
    } finally {
      testRunner.release();
    }
    return reportedMediaLoadData[0];
  }

  /**
   * Wraps the specified timeline in a {@link ClippingMediaSource} and returns the clipped timeline.
   */
  private static Timeline getClippedTimeline(Timeline timeline, long startUs, long endUs)
      throws IOException {
    FakeMediaSource fakeMediaSource = new FakeMediaSource(timeline, null);
    ClippingMediaSource mediaSource = new ClippingMediaSource(fakeMediaSource, startUs, endUs);
    MediaSourceTestRunner testRunner = new MediaSourceTestRunner(mediaSource, null);
    try {
      Timeline clippedTimeline = testRunner.prepareSource();
      testRunner.releaseSource();
      fakeMediaSource.assertReleased();
      return clippedTimeline;
    } finally {
      testRunner.release();
    }
  }
}
