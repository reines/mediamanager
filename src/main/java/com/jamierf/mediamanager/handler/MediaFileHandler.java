package com.jamierf.mediamanager.handler;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.jamierf.mediamanager.db.FileDatabase;
import com.jamierf.mediamanager.listeners.MediaFileListener;
import com.jamierf.mediamanager.managers.DownloadDirManager;
import io.dropwizard.util.Size;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.regex.Pattern;

public class MediaFileHandler implements FileTypeHandler {

	private static final ImmutableSet<String> EXTENSIONS = ImmutableSet.of("avi", "mkv", "mp4", "divx");
    private static final Pattern MEDIA_REJECT_PATTERN = Pattern.compile("\\bsample\\b", Pattern.CASE_INSENSITIVE);
    private static final HashFunction HASH_FUNCTION = Hashing.md5();
    private static final Size MIN_FILE_SIZE = Size.megabytes(100);
	private static final Logger LOG = LoggerFactory.getLogger(MediaFileHandler.class);

    public static boolean acceptFileExtension(String path) {
        final String extension = DownloadDirManager.getFileExtension(path);
        return EXTENSIONS.contains(extension) && !MEDIA_REJECT_PATTERN.matcher(path).find();
    }

    public static boolean acceptFileSize(long bytes) {
        return bytes >= MIN_FILE_SIZE.toBytes();
    }


    private final File destDir;
    private final boolean move;
    private final MediaFileListener listener;
    private final FileDatabase files;

    public MediaFileHandler(File destDir, boolean move, MediaFileListener listener, FileDatabase files) {
        this.destDir = destDir;
        this.move = move;
        this.listener = listener;
        this.files = files;

        if (!destDir.exists())
            destDir.mkdirs();
	}

	@Override
	public Collection<String> getHandledExtensions() {
		return EXTENSIONS;
	}

    protected boolean acceptFile(File file) throws IOException {
        final String path = file.getName();

        if (!MediaFileHandler.acceptFileExtension(path)) {
            LOG.trace("Rejecting unacceptable file extension: {}", file);
            return false;
        }

        if (files.isHandled(path)) {
            LOG.trace("Rejecting already handled file: {}", file);
            return false;
        }

        final long size = FileUtils.sizeOf(file);
        if (!acceptFileSize(size)) {
            LOG.trace("Rejecting too small file: {} ({} bytes)", file, size);
            return false;
        }

        return true;
    }

	@Override
	public void handleFile(String relativePath, File file) throws IOException {
        // Only accept certain files
        if (!this.acceptFile(file)) {
            // If we were passed the file but didn't accept it then delete it

            if (LOG.isDebugEnabled())
                LOG.debug("Deleting media file that was rejected: {}", file.getName());

            file.delete();
            return;
        }

		final File destFile = new File(destDir, file.getName());
		if (destFile.exists())
			throw new IOException("Skipping already existing media file: " + file.getName());

		// Make the parent directory if required
		final File destDir = destFile.getParentFile();
		if (!destDir.exists())
			destDir.mkdirs();

        final HashCode hash = Files.hash(file, HASH_FUNCTION);

		if (move) {
			if (LOG.isDebugEnabled())
				LOG.debug("Moving {} ({}={}) to {}", relativePath, HASH_FUNCTION, hash, destFile.getAbsoluteFile());

            Files.move(file, destFile);
		}
		else {
			if (LOG.isDebugEnabled())
				LOG.debug("Copying {} ({}={}) to {}", relativePath, HASH_FUNCTION, hash, destFile.getAbsoluteFile());

            Files.copy(file, destFile);
		}

        final HashCode destHash = Files.hash(destFile, HASH_FUNCTION);
        if (!destHash.equals(hash)) {
            LOG.warn("Mismatching hash after {} {} ({}={}) to {} ({}={})", (move ? "moving" : "copying"), file, HASH_FUNCTION, hash, destFile, HASH_FUNCTION, destHash);
        }

        listener.onNewItem(destFile);
        files.addHandled(file.getName());
	}
}
