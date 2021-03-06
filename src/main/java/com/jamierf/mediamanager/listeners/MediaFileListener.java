package com.jamierf.mediamanager.listeners;

import com.google.common.base.Optional;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.jamierf.mediamanager.db.ShowDatabase;
import com.jamierf.mediamanager.models.Episode;
import com.jamierf.mediamanager.models.Name;
import com.jamierf.mediamanager.models.NameAndQuality;
import com.jamierf.mediamanager.models.State;
import com.jamierf.mediamanager.parsing.EpisodeNameParser;
import com.jamierf.mediamanager.parsing.ItemListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class MediaFileListener implements ItemListener<File> {

    private static final Logger LOG = LoggerFactory.getLogger(MediaFileListener.class);
    private static final HashFunction HASH_FUNCTION = Hashing.md5();

    private static String getEpisodePath(Name name, String originalPath) {
        final StringBuilder builder = new StringBuilder();

        builder.append(name.getTitle());
        builder.append(File.separator);

        // If we have a season then create a sub folder
        final int season = name.getSeason();
        if (season > 0) {
            builder.append(String.format("Season %02d", name.getSeason()));
            builder.append(File.separator);
        }

        builder.append(originalPath);

        return builder.toString();
    }

    private final ShowDatabase shows;
    private final File destDir;
    private final EpisodeNameParser episodeNameParser;

    public MediaFileListener(ShowDatabase shows, File destDir, EpisodeNameParser episodeNameParser) {
        this.shows = shows;
        this.destDir = destDir;
        this.episodeNameParser = episodeNameParser;

        LOG.info("Using destination dir: {}", destDir.getAbsolutePath());
        if (!destDir.isDirectory()) {
            if (LOG.isDebugEnabled())
                LOG.debug("File destination directory '{}' doesn't exist, creating", destDir);

            destDir.mkdirs();
        }
    }

    private Episode getEpisode(String filename) throws Exception {
        final NameAndQuality nameAndQuality = episodeNameParser.parseFilename(filename);
        // We cannot parse this name, so we cannot do anything with it...
        if (nameAndQuality == null) {
            return null;
        }

        final Optional<Episode> episode = shows.get(nameAndQuality.getName());
        // The episode already exists, make a copy and mark it as existing
        if (episode.isPresent()) {
            return episode.get().copyWithState(State.EXISTS);
        }

        return new Episode(nameAndQuality.getName(), State.EXISTS);
    }

    @Override
    public void onNewItem(File item) {
        // Default to just using the original filename
        String path = item.getName();

        try {
            final Episode episode = this.getEpisode(item.getName());
            if (episode == null) {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed to parse unrecognised episode name: {}", item.getName());

                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Marking new media episode {} as existing", episode.getName());

            // Update to the path to insert the episode to the correct folder
            path = MediaFileListener.getEpisodePath(episode.getName(), path);

            shows.addOrUpdate(episode);
        }
        catch (Exception e) {
            LOG.warn("Failed to mark new media file as existing", e);
        }
        finally {
            final File destFile = new File(destDir.getAbsolutePath() + File.separator + path);
            try {
                // Ensure the parent directory exists
                final File directory = destFile.getParentFile();
                directory.mkdirs();

                final HashCode hash = Files.hash(item, HASH_FUNCTION);

                if (LOG.isDebugEnabled())
                    LOG.debug("Renaming {} ({}={}) to {}", item.getAbsolutePath(), HASH_FUNCTION, hash, destFile.getAbsolutePath());

                Files.move(item, destFile);

                final HashCode destHash = Files.hash(destFile, HASH_FUNCTION);
                if (!destHash.equals(hash)) {
                    LOG.warn("Mismatching hash after renaming {} ({}={}) to {} ({}={})", item, HASH_FUNCTION, hash, destFile, HASH_FUNCTION, destHash);
                }
            }
            catch (IOException e) {
                LOG.error("Error moving temp file to " + destFile, e);
            }
        }
    }

    @Override
    public void onException(Throwable cause) {
        LOG.warn("Exception with media file", cause);
    }
}
