package com.jamierf.mediamanager.managers;

import com.google.common.collect.Maps;
import com.jamierf.mediamanager.config.FileConfiguration;
import com.jamierf.mediamanager.handler.FileHandler;
import com.jamierf.mediamanager.handler.FileTypeHandler;
import com.jamierf.mediamanager.io.DirMonitor;
import com.jamierf.mediamanager.io.FileListener;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class DownloadDirManager implements FileListener, Managed {

	private static final Logger LOG = LoggerFactory.getLogger(DownloadDirManager.class);

    public static String getFileName(String name) {
        final int delim = name.lastIndexOf('.');
        if (delim < 0)
            return name;

        return name.substring(0, delim);
    }

    public static String getFileExtension(String name) {
        final int delim = name.lastIndexOf('.');
        if (delim < 0)
            return "";

        return name.substring(delim + 1).toLowerCase();
    }

	private final DirMonitor monitor;
	private final Map<String, FileTypeHandler> fileHandlers;
	private final int pathTrimLength;
    private FileHandler directoryHandler;
    private FileHandler defaultHandler;

	public DownloadDirManager(FileConfiguration config) throws IOException {
        final File watchDir = config.getWatchDir();

        LOG.info("Using download dir: {}", watchDir.getAbsolutePath());
        if (!watchDir.exists()) {
            if (LOG.isDebugEnabled())
                LOG.debug("File download directory '{}' doesn't exist, creating", watchDir);

            watchDir.mkdirs();
        }

		monitor = new DirMonitor(watchDir);
		monitor.addListener(this);

		fileHandlers = Maps.newHashMap();
		pathTrimLength = config.getWatchDir().getAbsolutePath().length();

        directoryHandler = null;
        defaultHandler = null;
	}

	public void addFileTypeHandler(FileTypeHandler handler) {
		for (String ext : handler.getHandledExtensions())
			fileHandlers.put(ext.toLowerCase(), handler);
	}

    public void setDirectoryHandler(FileHandler directoryHandler) {
        this.directoryHandler = directoryHandler;
    }

    public void setDefaultFileHandler(FileHandler defaultHandler) {
        this.defaultHandler = defaultHandler;
    }

    @Override
	public void start() {
		monitor.start();
	}

    @Override
	public void stop() {
		monitor.stop();
	}

    private FileHandler getFileHandler(String path, File file) {
        if (file.isDirectory()) {
            // If we have a directory handler, use that
            if (directoryHandler != null)
                return directoryHandler;
        }
        else {
            final String extension = DownloadDirManager.getFileExtension(path);

            // If we have a handler for this extension, use that
            if (fileHandlers.containsKey(extension))
                return fileHandlers.get(extension);
        }

        // Use the default handler
        return defaultHandler;
    }

    @Override
	public void onNewFile(File file) {
		final String path = file.getAbsolutePath().substring(pathTrimLength).replaceAll("\\\\", "/"); // trim the start then fix windows style slashes

		try {
            final FileHandler handler = this.getFileHandler(path, file);
            if (handler == null) {
                if (LOG.isTraceEnabled())
                    LOG.trace("Unhandled file: " + path);

                return;
            }

			handler.handleFile(path, file);
		}
		catch (Exception e) {
            LOG.warn("Error handling file", e);
		}
	}
}
