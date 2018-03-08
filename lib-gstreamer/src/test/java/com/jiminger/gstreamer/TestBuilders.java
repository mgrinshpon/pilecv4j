package com.jiminger.gstreamer;

import static com.jiminger.gstreamer.util.GstUtils.instrument;
import static net.dempsy.utils.test.ConditionPoll.poll;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;

import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.elements.URIDecodeBin;
import org.freedesktop.gstreamer.event.EOSEvent;
import org.junit.Test;

import com.jiminger.gstreamer.guard.ElementWrap;
import com.jiminger.gstreamer.guard.GstMain;
import com.jiminger.gstreamer.util.FrameCatcher;

public class TestBuilders {
    final static URI STREAM = new File(
            TestBuilders.class.getClassLoader().getResource("test-videos/Libertas-70sec.mp4").getFile()).toURI();

    @Test
    public void testSimplePipeline() throws Exception {
        try (final GstMain m = new GstMain(TestBuilders.class);) {
            final FrameCatcher fc = new FrameCatcher("framecatcher");
            try (ElementWrap<Pipeline> ew = new ElementWrap<>(new BinBuilder()
                    .delayed(new URIDecodeBin("source")).with("uri", STREAM.toString())
                    .make("videoscale")
                    .make("videoconvert")
                    .caps("video/x-raw,width=640,height=480")
                    .add(fc.sink)
                    .buildPipeline());) {
                final Pipeline pipe = ew.element;
                instrument(pipe);
                pipe.play();
                Thread.sleep(1000);
                pipe.sendEvent(new EOSEvent());
                pipe.stop();
                assertTrue(poll(o -> !pipe.isPlaying()));
            }

            // one seconds worth of frames should be more than 25 and less than 35 (actually, should be 29)
            final int numFrames = fc.frames.size();
            assertTrue(25 < numFrames);
            assertTrue(35 > numFrames);
        }
    }
}