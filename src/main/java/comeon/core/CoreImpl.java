package comeon.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import comeon.core.events.PictureTransferDoneEvent;
import comeon.core.events.PictureTransferFailedEvent;
import comeon.core.events.PictureTransferStartingEvent;
import comeon.core.events.UploadDoneEvent;
import comeon.core.events.UploadStartingEvent;
import comeon.core.extmetadata.ExternalMetadataSource;
import comeon.mediawiki.FailedLoginException;
import comeon.mediawiki.FailedLogoutException;
import comeon.mediawiki.FailedUploadException;
import comeon.mediawiki.MediaWiki;
import comeon.mediawiki.MediaWikiFactory;
import comeon.mediawiki.NotLoggedInException;
import comeon.model.Picture;
import comeon.model.Picture.State;
import comeon.model.Template;
import comeon.model.Wiki;
import comeon.ui.actions.PictureRemovedEvent;
import comeon.ui.actions.PicturesAddedEvent;
import comeon.wikis.ActiveWikiChangeEvent;
import comeon.wikis.Wikis;

@Singleton
public final class CoreImpl implements Core {
  private static final Logger LOGGER = LoggerFactory.getLogger(CoreImpl.class);

  private final List<Picture> pictures;

  private final ExecutorService pool;

  private final Wikis wikis;

  private final EventBus bus;

  private final PicturesBatchFactory picturesBatchFactory;
  
  private final MediaWikiFactory mediaWikiFactory;
  
  private final Queue<Future<Void>> currentTasks;

  private MediaWiki activeMediaWiki;

  @Inject
  private CoreImpl(final Wikis wikis, final ExecutorService pool, final EventBus bus,
      final PicturesBatchFactory picturesBatchFactory, final MediaWikiFactory mediaWikiFactory) {
    this.pictures = new ArrayList<>();
    this.currentTasks = new ConcurrentLinkedQueue<>();
    this.pool = pool;
    this.bus = bus;
    this.wikis = wikis;
    this.picturesBatchFactory = picturesBatchFactory;
    this.mediaWikiFactory = mediaWikiFactory;
    final Wiki activeWiki = wikis.getActiveWiki();
    if (activeWiki == null) {
      throw new IllegalStateException("There must be one active wiki.");
    } else {
      this.activeMediaWiki = mediaWikiFactory.build(activeWiki);
    }
  }

  @Override
  public void addPictures(final File[] files, final Template defautTemplate,
      final ExternalMetadataSource<?> externalMetadataSource) {
    externalMetadataSource.loadMetadata();
    final PicturesBatch picturesReader = picturesBatchFactory.makePicturesBatch(files, defautTemplate,
        externalMetadataSource);
    final List<Picture> newPictures = picturesReader.readFiles(wikis.getActiveWiki().getUser()).getPictures();
    this.pictures.addAll(newPictures);
    bus.post(new PicturesAddedEvent(Collections.unmodifiableList(newPictures)));
  }

  @Override
  public void removePicture(final Picture picture) {
    pictures.remove(picture);
    bus.post(new PictureRemovedEvent(picture));
  }

  @Override
  public List<Picture> getPictures() {
    return pictures;
  }

  private boolean shouldUpload(final Picture picture) {
    return !State.UploadedSuccessfully.equals(picture.getState());
  }

  @Override
  public int countPicturesToBeUploaded() {
    return filterPicturesToBeUploaded().size();
  }
  
  private List<Picture> filterPicturesToBeUploaded() {
    final List<Picture> picturesToBeUploaded = new ArrayList<>(pictures.size());
    for (final Picture picture : pictures) {
      if (shouldUpload(picture)) {
        picturesToBeUploaded.add(picture);
      }
    }
    return picturesToBeUploaded;
  }

  private class UploadTask implements Callable<Void> {
    private final Logger taskLogger = LoggerFactory.getLogger(UploadTask.class);
    
    private final Picture picture;
    
    public UploadTask(final Picture picture) {
      this.picture = picture;
    }
    
    @Override
    public Void call() throws Exception {
      try {
        taskLogger.debug("Starting upload of {}", picture.getFileName());
        final ProgressListenerAdapter progressListener = new ProgressListenerAdapter();
        bus.post(new PictureTransferStartingEvent(picture, progressListener));
        activeMediaWiki.upload(picture, progressListener);
        picture.setState(State.UploadedSuccessfully);
        bus.post(new PictureTransferDoneEvent(picture));
        taskLogger.debug("Finished upload of {}", picture.getFileName());
      } catch (final NotLoggedInException | FailedLoginException | FailedUploadException | IOException e) {
        taskLogger.warn("Failed upload of {}", picture.getFileName(), e);
        picture.setState(State.FailedUpload);
        bus.post(new PictureTransferFailedEvent(picture, e));
      }
      return null;
    }
    
    @Override
    public boolean equals(final Object obj) {
      final boolean isEqual;
      if (obj == null) {
        isEqual = false;
      } else if (obj instanceof UploadTask) {
        final UploadTask task = (UploadTask) obj;
        isEqual = task.picture.equals(this.picture);
      } else {
        isEqual = super.equals(obj);
      }
      return isEqual;
    }
  }
  
  @Override
  public void uploadPictures() {
    final List<Picture> picturesToBeUploaded = this.filterPicturesToBeUploaded();
    LOGGER.info("Uploading {} pictures to {}.", picturesToBeUploaded.size(), activeMediaWiki.getName());
    bus.post(new UploadStartingEvent(picturesToBeUploaded));
    final List<Future<Void>> tasks = new ArrayList<>(picturesToBeUploaded.size());
    for (final Picture picture : picturesToBeUploaded) {
      final UploadTask task = new UploadTask(picture);
      final Future<Void> taskResult = pool.submit(task);
      tasks.add(taskResult);
    }
    currentTasks.addAll(tasks);
    try {
      for (final Future<Void> task : tasks) {
        try {
          task.get();
        } catch (final CancellationException e) {
          LOGGER.debug("Task was cancelled", e);
        } catch (final ExecutionException e) {
          LOGGER.warn("Task execution failed", e);
        } finally {
          currentTasks.remove(task);
        }
      }
    } catch (final InterruptedException e) {
      Thread.interrupted();
      LOGGER.warn("We were interrupted while waiting for uploads to complete", e);
    } finally {
      try {
        activeMediaWiki.logout();
      } catch (final FailedLogoutException e) {
        LOGGER.warn("Couldn't close Mediawiki session properly", e);
      }
      bus.post(new UploadDoneEvent());
      LOGGER.info("Upload done.");
    }
  }
  
  @Override
  public void abort() {
    for (final Future<Void> task : currentTasks) {
      if (task.cancel(true)) {
        currentTasks.remove(task);
      }
    }
  }
  
  @Subscribe
  public void handleActiveWikiChangeEvent(final ActiveWikiChangeEvent event) {
    if (this.activeMediaWiki.isLoggedIn()) {
      try {
        this.activeMediaWiki.logout();
      } catch (final FailedLogoutException e) {
        LOGGER.warn("Failed implicit logout", e);
      }
    }
    this.activeMediaWiki = mediaWikiFactory.build(wikis.getActiveWiki());
    for (final Picture picture : pictures) {
      picture.renderTemplate(wikis.getActiveWiki().getUser());
    }
  }
}
