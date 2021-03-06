package com.jamierf.mediamanager;

import com.codahale.metrics.MetricRegistry;
import com.jamierf.mediamanager.config.*;
import com.jamierf.mediamanager.db.FileDatabase;
import com.jamierf.mediamanager.db.QualityDatabase;
import com.jamierf.mediamanager.db.ShowDatabase;
import com.jamierf.mediamanager.db.azure.AzureTableFileDatabase;
import com.jamierf.mediamanager.db.azure.AzureTableQualityDatabase;
import com.jamierf.mediamanager.db.azure.AzureTableShowDatabase;
import com.jamierf.mediamanager.downloader.Downloader;
import com.jamierf.mediamanager.downloader.WatchDirDownloader;
import com.jamierf.mediamanager.filters.QualityFilter;
import com.jamierf.mediamanager.handler.GarbageFileHandler;
import com.jamierf.mediamanager.handler.MediaFileHandler;
import com.jamierf.mediamanager.handler.MediaRarFileHandler;
import com.jamierf.mediamanager.healthchecks.DatabaseHealthcheck;
import com.jamierf.mediamanager.healthchecks.ParserHealthcheck;
import com.jamierf.mediamanager.io.StaticAssetForwarder;
import com.jamierf.mediamanager.io.retry.DelayedJerseyRetryManager;
import com.jamierf.mediamanager.io.retry.RetryManager;
import com.jamierf.mediamanager.listeners.CalendarItemListener;
import com.jamierf.mediamanager.listeners.DownloadableItemListener;
import com.jamierf.mediamanager.listeners.DownloadableItemListenerProxy;
import com.jamierf.mediamanager.listeners.MediaFileListener;
import com.jamierf.mediamanager.managers.BackfillManager;
import com.jamierf.mediamanager.managers.DownloadDirManager;
import com.jamierf.mediamanager.managers.FeedManager;
import com.jamierf.mediamanager.parsing.EpisodeNameParser;
import com.jamierf.mediamanager.parsing.FeedParser;
import com.jamierf.mediamanager.parsing.ical.CalendarItem;
import com.jamierf.mediamanager.parsing.ical.parsers.CalendarParser;
import com.jamierf.mediamanager.parsing.rss.RSSItem;
import com.jamierf.mediamanager.parsing.rss.parsers.RSSParser;
import com.jamierf.mediamanager.parsing.search.SearchItem;
import com.jamierf.mediamanager.parsing.search.SearchParser;
import com.jamierf.mediamanager.resources.BackfillResource;
import com.jamierf.mediamanager.resources.MediaManagerResource;
import com.jamierf.mediamanager.resources.ShowsResource;
import com.microsoft.windowsazure.services.core.storage.StorageException;
import com.sun.jersey.api.client.Client;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.views.ViewBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;

public class MediaManager extends Application<MediaManagerConfiguration> {

    private static final Logger LOG = LoggerFactory.getLogger(MediaManager.class);

	public static void main(String[] args) throws Exception {
        new MediaManager().run(args);
	}

    private static ShowDatabase buildShowDatabase(final DatabaseConfiguration config, final MetricRegistry metrics) throws StorageException {
        return new AzureTableShowDatabase(config.getString("accountName"), config.getString("accountKey"), metrics);
    }

    private static FileDatabase buildFileDatabase(final DatabaseConfiguration config, final MetricRegistry metrics) throws StorageException {
        return new AzureTableFileDatabase(config.getString("accountName"), config.getString("accountKey"), metrics);
    }

    private static QualityDatabase buildQualityDatabase(final DatabaseConfiguration config, final MetricRegistry metrics) throws StorageException {
        return new AzureTableQualityDatabase(config.getString("accountName"), config.getString("accountKey"), metrics);
    }

    private static QualityFilter buildQualityFilter(TorrentConfiguration config, final QualityDatabase db) {
        return new QualityFilter(db, config.getPrimaryQualities(), config.getSecondaryQualities(), config.getPrimaryQualityTimeout());
    }

    private static DownloadableItemListener buildDownloadableListener(QualityFilter qualityFilter, ShowDatabase shows, Downloader downloader, EpisodeNameParser episodeNameParser) {
        return new DownloadableItemListener(qualityFilter, shows, downloader, episodeNameParser);
    }

    private static MediaFileListener buildMediaListener(ShowDatabase shows, FileConfiguration config, EpisodeNameParser episodeNameParser) {
        return new MediaFileListener(shows, config.getDestinationDir(), episodeNameParser);
    }

    private static BackfillManager buildBackfillManager(TorrentConfiguration config, ShowDatabase shows, DownloadableItemListener downloadableItemListener, Client client, RetryManager retryManager) throws ClassNotFoundException {
        final BackfillManager backfill = new BackfillManager(shows, config.getBackfillDelay());
        backfill.addListener(new DownloadableItemListenerProxy<SearchItem>(downloadableItemListener));

        // Load in all configured search parsers
        final Map<String, ParserConfiguration> searchers = config.getSearchers();
        for (String name : searchers.keySet()) {
            final ParserConfiguration parserConfig = searchers.get(name);
            final SearchParser parser = SearchParser.getInstance(SearchParser.class, name, client, retryManager, parserConfig);

            backfill.addParser(parser);
        }

        return backfill;
    }

    private static FeedManager<CalendarItem> buildCalendarFeedManager(CalendarConfiguration config, ShowDatabase shows, BackfillManager backfillManager, Client client, RetryManager retryManager, EpisodeNameParser episodeNameParser) throws ClassNotFoundException {
        final FeedManager<CalendarItem> calendarFeed = new FeedManager<>(config.getUpdateDelay());
        final CalendarItemListener calendarListener = new CalendarItemListener(shows, backfillManager, episodeNameParser);

        calendarFeed.addListener(calendarListener);

        // Load in all configured calendar parsers
        final Map<String, ParserConfiguration> icalParsers = config.getParsers();
        for (String name : icalParsers.keySet()) {
            final ParserConfiguration parserConfig = icalParsers.get(name);
            final CalendarParser parser = FeedParser.getInstance(CalendarParser.class, name, client, retryManager, parserConfig);

            calendarFeed.addParser(parser);
        }

        return calendarFeed;
    }

    private static Downloader buildTorrentFileManager(TorrentConfiguration config, Client client, RetryManager retryManager) {
        return new WatchDirDownloader(client, retryManager, config.getWatchDir());
    }

    private static FeedManager<RSSItem> buildTorrentFeedManager(TorrentConfiguration config, DownloadableItemListener downloadableItemListener, Client client, RetryManager retryManager) throws ClassNotFoundException {
        final FeedManager<RSSItem> torrentFeed = new FeedManager<>(config.getUpdateDelay());
        torrentFeed.addListener(new DownloadableItemListenerProxy<RSSItem>(downloadableItemListener));

        // Load in all configured torrent parsers
        final Map<String, ParserConfiguration> feeders = config.getFeeders();
        for (String name : feeders.keySet()) {
            final ParserConfiguration parserConfig = feeders.get(name);
            final RSSParser parser = FeedParser.getInstance(RSSParser.class, name, client, retryManager, parserConfig);

            torrentFeed.addParser(parser);
        }

        return torrentFeed;
    }

    private static DownloadDirManager buildDownloadDirManager(FileConfiguration config, MediaFileListener mediaListener, FileDatabase files) throws IOException {
        final DownloadDirManager downloadDirManager = new DownloadDirManager(config);

        final File tempDir = config.getTempDir();
        LOG.info("Using temp dir: {}", tempDir.getAbsolutePath());
        if (!tempDir.isDirectory()) {
            if (LOG.isDebugEnabled())
                LOG.debug("File temp directory '{}' doesn't exist, creating", tempDir);

            tempDir.mkdirs();
        }

        downloadDirManager.addFileTypeHandler(new MediaRarFileHandler(tempDir, config.isDeleteOriginals(), mediaListener, files));
        downloadDirManager.addFileTypeHandler(new MediaFileHandler(tempDir, config.isDeleteOriginals(), mediaListener, files));

        // Handle garbage files (only if we want to delete originals)!
        if (config.isDeleteOriginals()) {
            final GarbageFileHandler garbageHandler = new GarbageFileHandler();
            downloadDirManager.addFileTypeHandler(garbageHandler);
            downloadDirManager.setDirectoryHandler(garbageHandler);
        }

        return downloadDirManager;
    }

    @Override
    public void initialize(Bootstrap<MediaManagerConfiguration> bootstrap) {
        // Enable views
        bootstrap.addBundle(new ViewBundle());

        // Enable assets
        bootstrap.addBundle(new AssetsBundle());
    }

    @Override
    public void run(MediaManagerConfiguration config, Environment environment) throws Exception {
        final JerseyClientBuilder clientFactory = new JerseyClientBuilder(environment).using(config.getHttpClientConfiguration());
        final RetryManager retryManager = new DelayedJerseyRetryManager(environment.metrics(), MediaManager.class, config.getRetryConfiguration());

        // Initialise the shows database - this stores what episodes we should be watching for
        final ShowDatabase shows = MediaManager.buildShowDatabase(config.getDatabaseConfiguration(), environment.metrics());
        environment.lifecycle().manage(shows);

        // Initialise the shows database - this stores what files have already been handled
        final FileDatabase files = MediaManager.buildFileDatabase(config.getDatabaseConfiguration(), environment.metrics());
        environment.lifecycle().manage(files);

        final QualityDatabase quality = MediaManager.buildQualityDatabase(config.getDatabaseConfiguration(), environment.metrics());
        environment.lifecycle().manage(quality);

        // Initialise the torrent file manager - this is responsible for taking a torrent file URL and downloading the torrent contents
        final Downloader torrentFileManager = MediaManager.buildTorrentFileManager(config.getTorrentConfiguration(), clientFactory.build("file"), retryManager);
        environment.lifecycle().manage(torrentFileManager);

        // Initialise the episode name parser - this parses filenames and torrent titles in to an episode name, number, season number, and quality
        final EpisodeNameParser episodeNameParser = new EpisodeNameParser(config.getAliases());

        // Initialise filter for qualities
        final QualityFilter qualityFilter = buildQualityFilter(config.getTorrentConfiguration(), quality);

        // Initialise the downloadable item listener - this listens for downloadable items and passes them to the torrent file manager
        final DownloadableItemListener downloadableListener = MediaManager.buildDownloadableListener(qualityFilter, shows, torrentFileManager, episodeNameParser);

        // Initialise the media item listener - this listens for downloaded items and marks them as such in the database
        final MediaFileListener mediaListener = MediaManager.buildMediaListener(shows, config.getFileConfiguration(), episodeNameParser);

        // Initialise the backfill manager - this searches for missing episodes on demand
        final BackfillManager backfillManager = MediaManager.buildBackfillManager(config.getTorrentConfiguration(), shows, downloadableListener, clientFactory.build("backfill"), retryManager);
        environment.lifecycle().manage(backfillManager);

        // Initialise the calendar feed manager - this periodically parses the known calendar feeds to look for new episodes we want to watch for
        final FeedManager<CalendarItem> calendarFeedManager = MediaManager.buildCalendarFeedManager(config.getCalendarConfiguration(), shows, backfillManager, clientFactory.build("calendar-feed"), retryManager, episodeNameParser);
        environment.lifecycle().manage(calendarFeedManager);

        // Initialise the torrent feed manager - this periodically parses the known torrent RSS feeds to look for new episodes we are watching for
        final FeedManager<RSSItem> torrentFeedManager = MediaManager.buildTorrentFeedManager(config.getTorrentConfiguration(), downloadableListener, clientFactory.build("torrent-feed"), retryManager);
        environment.lifecycle().manage(torrentFeedManager);

        // Initialise the download dir manager - this listens for new files in the download directory and moves the wanted ones to a specified directory
		final DownloadDirManager downloadDirManager = MediaManager.buildDownloadDirManager(config.getFileConfiguration(), mediaListener, files);
        environment.lifecycle().manage(downloadDirManager);

        // Add a filter to redirect favicon to the static assets directory
        environment.servlets().addFilter("favicon-filter", new StaticAssetForwarder())
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/favicon.*");

        // Add API endpoints
        environment.jersey().register(new MediaManagerResource());
        environment.jersey().register(new ShowsResource(shows));
        environment.jersey().register(new BackfillResource(shows, backfillManager, episodeNameParser));

        // Add ping health checks for torrents, calendar, and backfill
        environment.healthChecks().register("parsers", new ParserHealthcheck(config.getHttpClientConfiguration().getConnectionTimeout(), torrentFeedManager, calendarFeedManager, backfillManager));
        environment.healthChecks().register("database", new DatabaseHealthcheck(shows));
    }
}
