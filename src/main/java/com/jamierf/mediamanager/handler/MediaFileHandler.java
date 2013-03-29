package com.jamierf.mediamanager.handler;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.jamierf.mediamanager.listeners.MediaFileListener;
import com.jamierf.mediamanager.managers.DownloadDirManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.regex.Pattern;

public class MediaFileHandler implements FileTypeHandler {

	private static final ImmutableSet<String> EXTENSIONS = ImmutableSet.of("avi", "mkv", "mp4", "divx");
    private static final Pattern MEDIA_REJECT_PATTERN = Pattern.compile("\\bsample\\b", Pattern.CASE_INSENSITIVE);

    public static boolean acceptFile(String path) {
        final String extension = DownloadDirManager.getFileExtension(path);
        return EXTENSIONS.contains(extension) && !MEDIA_REJECT_PATTERN.matcher(path).find();
    }

	private static final Logger LOG = LoggerFactory.getLogger(MediaFileHandler.class);

    private final File destDir;
    private final boolean move;
    private final MediaFileListener listener;

    public MediaFileHandler(File destDir, boolean move, MediaFileListener listener) {
        this.destDir = destDir;
        this.move = move;
        this.listener = listener;

        if (!destDir.exists())
            destDir.mkdirs();
	}

	@Override
	public Collection<String> getHandledExtensions() {
		return EXTENSIONS;
	}

	@Override
	public void handleFile(String relativePath, File file) throws IOException {
        // Only accept certain files
        if (!MediaFileHandler.acceptFile(file.getName())) {
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

		if (move) {
			if (LOG.isDebugEnabled())
				LOG.debug("Moving {} to {}", relativePath, destFile.getAbsoluteFile());

            Files.move(file, destFile);
		}
		else {
			if (LOG.isDebugEnabled())
				LOG.debug("Copying {} to {}", relativePath, destFile.getAbsoluteFile());

            Files.copy(file, destFile);
		}

        listener.onNewItem(destFile);
	}
}
