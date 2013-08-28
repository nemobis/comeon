package comeon.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import org.apache.commons.beanutils.DynaBean;
import org.apache.commons.beanutils.DynaProperty;
import org.apache.commons.beanutils.LazyDynaBean;
import org.apache.commons.beanutils.LazyDynaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.TagDescriptor;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import comeon.model.Picture;
import comeon.model.Template;
import comeon.model.User;
import comeon.model.processors.PreProcessor;
import comeon.model.processors.Processors;

final class Pictures {
  private static final Logger LOGGER = LoggerFactory.getLogger(Pictures.class);

  private final File[] files;

  private final Template defaultTemplate;

  private final List<Picture> pictures;

  private final ExecutorService pool;

  private final CountDownLatch latch;

  Pictures(final File[] files, final Template defautTemplate, final ExecutorService pool) {
    this.files = files;
    this.defaultTemplate = defautTemplate;
    this.pool = pool;
    this.pictures = Collections.synchronizedList(new ArrayList<Picture>(files.length));
    this.latch = new CountDownLatch(files.length);
  }

  public Pictures readFiles(final User user) {
    for (final File file : files) {
      pool.execute(new PictureReader(file, user));
    }

    try {
      latch.await();
    } catch (final InterruptedException e) {
      Thread.interrupted();
    }

    return this;
  }

  public List<Picture> getPictures() {
    return pictures;
  }

  private final class PictureReader implements Runnable {
    private static final String NON_WORD_CHARS = "[^\\w]";

    private final File file;

    private final User user;

    private PictureReader(final File file, final User user) {
      this.file = file;
      this.user = user;
    }

    @Override
    public void run() {
      final String fileName = file.getAbsolutePath();
      try {
        final Metadata rawMetadata = ImageMetadataReader.readMetadata(file);
        final ExifThumbnailDirectory thumbnailDirectory = rawMetadata.getDirectory(ExifThumbnailDirectory.class);
        final byte[] thumbnail;
        if (thumbnailDirectory.hasThumbnailData()) {
          thumbnail = thumbnailDirectory.getThumbnailData();
        } else {
          thumbnail = new byte[0];
        }
        final Map<String, Object> metadata = new HashMap<>(rawMetadata.getDirectoryCount());
        for (final Directory directory : rawMetadata.getDirectories()) {
          copy(metadata, directory);
          preProcess(metadata, directory);
        }
        final Picture picture = new Picture(file, fileName, defaultTemplate, metadata, thumbnail);
        picture.renderTemplate(user);
        pictures.add(picture);
      } catch (final ImageProcessingException e) {
        LOGGER.warn("Can't read metadata from {}", fileName, e);
      } catch (final IOException e) {
        LOGGER.warn("Can't read file {}", fileName, e);
      }
      latch.countDown();
    }

    private void copy(final Map<String, Object> metadata, final Directory directory) {
      final TagDescriptor<?> descriptor = MetadataHelper.getDescriptor(directory);
      final List<DynaProperty> properties = new LinkedList<>();
      for (final Tag tag : directory.getTags()) {
        final DynaProperty property = new DynaProperty(tag.getTagName().replaceAll(NON_WORD_CHARS, ""), String.class);
        properties.add(property);
      }
      final LazyDynaClass directoryClass = new LazyDynaClass(directory.getName(), null,
          properties.toArray(new DynaProperty[properties.size()]));
      directoryClass.setReturnNull(true);
      final DynaBean directoryMetadata = new LazyDynaBean(directoryClass);
      for (final Tag tag : directory.getTags()) {
        directoryMetadata.set(tag.getTagName().replaceAll(NON_WORD_CHARS, ""),
            descriptor.getDescription(tag.getTagType()));
      }
      metadata.put(directory.getName(), directoryMetadata);
    }

    private void preProcess(final Map<String, Object> metadata, final Directory directory) {
      final Set<PreProcessor> preProcessors = Processors.getInstance().getPreProcessors(directory.getClass());
      for (final PreProcessor preProcessor : preProcessors) {
        preProcessor.process(directory, metadata);
      }
    }
  }
}