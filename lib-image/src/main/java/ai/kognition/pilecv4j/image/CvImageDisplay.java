package ai.kognition.pilecv4j.image;

import static net.dempsy.util.Functional.uncheck;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CvImageDisplay implements ImageDisplay {
   static {
      CvRasterAPI._init();
   }

   private static Logger LOGGER = LoggerFactory.getLogger(CvImageDisplay.class);

   // ==============================================================
   // This is basically a single threaded executor but we need to
   // check cv::waitKey or nothing happens in OpenCv::HighGUI
   private static ArrayBlockingQueue<Consumer<WindowsState>> commands = new ArrayBlockingQueue<>(2);
   public static AtomicBoolean stillRunningEvents = new AtomicBoolean(true);

   @FunctionalInterface
   private static interface CvKeyPressCallback {
      public String keyPressed(int keyPressed);
   }

   private static class WindowsState {
      final Set<String> windows = new HashSet<>();
      final Map<String, CvKeyPressCallback> callbacks = new HashMap<>();
   }

   static {
      final Thread mainEvent = new Thread(() -> {

         final WindowsState state = new WindowsState();

         while(stillRunningEvents.get()) {
            // System.out.println(state.windows);
            try {
               if(state.windows.size() > 0) {
                  // then we can check for a key press.
                  final int key = CvRasterAPI.CvRaster_fetchEvent(1);
                  final List<String> toCloseUp = state.callbacks.values().stream()
                        .map(cb -> cb.keyPressed(key))
                        .filter(n -> n != null)
                        .map(n -> {
                           // need to close the window and cleanup.
                           CvRasterAPI.CvRaster_destroyWindow(n);
                           // okay. We got here so the window should be closed.
                           return n; // we're building a list of objects to closeup
                        })
                        .collect(Collectors.toList());

                  toCloseUp.forEach(n -> {
                     state.windows.remove(n);
                     state.callbacks.remove(n);
                  });
               }

               // we need to check to see if there's any commands to execute.
               final Consumer<WindowsState> cmd = commands.poll();
               if(cmd != null) {
                  try {
                     cmd.accept(state);
                  } catch(final Exception e) {
                     LOGGER.error("OpenCv::HighGUI command \"{}\" threw an excetion.", cmd, e);
                  }
               }
            } catch(final Throwable th) {
               LOGGER.error("OpenCv::HighGUI CRITICAL ERROR! But yet, I persist.", th);
            }
         }

      }, "OpenCv::HighGUI event thread");

      mainEvent.setDaemon(true);
      mainEvent.start();
   }
   // ==============================================================

   private boolean closed = false;
   private final ShowKeyPressCallback callback;
   private final Runnable closeCallback;
   private final String name;
   private boolean shownYet = false;

   CvImageDisplay(final Mat mat, final String name, final Runnable closeCallback, final KeyPressCallback kpCallback) {

      this.closeCallback = closeCallback;
      this.name = name;

      // create a callback that ignores the keypress but polls the state of the closeNow
      this.callback = new ShowKeyPressCallback(name, kpCallback);

      if(mat != null) {
         doShow(mat, name, callback);

         while(!callback.shown.get())
            Thread.yield();
      }
   }

   @Override
   public void close() {
      LOGGER.trace("Closing window \"{}\"", name);
      closed = true;
      callback.closeNow.set(true);
      if(closeCallback != null)
         closeCallback.run();
   }

   @Override
   public void update(final Mat toUpdate) {
      if(closed) {
         LOGGER.trace("Attempting to update a closed window with {}", toUpdate);
         return;
      }

      if(callback.closeNow.get() && !closed) {
         close();
         LOGGER.debug("Attempting to update a closed window with {}", toUpdate);
         return;
      }

      if(!shownYet) {
         synchronized(this) {
            if(!shownYet)
               doShow(toUpdate, name, callback);
         }
      }

      // Shallow copy
      final CvMat old = callback.update.getAndSet(CvMat.shallowCopy(toUpdate));
      if(old != null)
         old.close();
   }

   private void doShow(final Mat mat, final String name, final CvKeyPressCallback callback) {
      LOGGER.debug("Showing image {} in window {} ", mat, name);

      // There is a problem with resource management since the mat is being passed to another thread
      final CvMat omat = CvMat.shallowCopy(mat);
      uncheck(() -> commands.put(s -> {
         try (CvMat lmat = omat) {
            CvRasterAPI.CvRaster_showImage(name, omat.nativeObj);
            // if we got here, we're going to assume the windows was created.
            if(callback != null)
               s.callbacks.put(name, callback);
            s.windows.add(name);
         }
      }));
      shownYet = true;
   }

   // a callback that ignores the keypress but polls the state of the closeNow
   private static class ShowKeyPressCallback implements CvKeyPressCallback {
      final AtomicBoolean shown = new AtomicBoolean(false);
      final AtomicBoolean closeNow = new AtomicBoolean(false);
      final AtomicReference<CvMat> update = new AtomicReference<CvMat>(null);
      final String name;
      final KeyPressCallback keyPressCallback;

      private boolean shownSet = false;

      private ShowKeyPressCallback(final String name, final KeyPressCallback keyPressCallback) {
         this.name = name;
         this.keyPressCallback = keyPressCallback;
      }

      @Override
      public String keyPressed(final int kp) {
         // the window is shown by the time we get here.
         if(!shownSet) {
            shown.set(true);
            shownSet = true;
         }

         try (final CvMat toUpdate = update.getAndSet(null);) {
            if(toUpdate != null)
               CvRasterAPI.CvRaster_updateWindow(name, toUpdate.nativeObj);
         }

         if(keyPressCallback != null && kp >= 0)

         {
            if(keyPressCallback.keyPressed(kp))
               closeNow.set(true);
         } else if(kp == 32) closeNow.set(true);

         return closeNow.get() ? name : null;
      }
   }
}
