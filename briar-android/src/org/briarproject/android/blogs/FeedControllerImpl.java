package org.briarproject.android.blogs;

import org.briarproject.android.api.AndroidNotificationManager;
import org.briarproject.android.api.BackgroundExecutor;
import org.briarproject.android.controller.handler.ResultExceptionHandler;
import org.briarproject.android.controller.handler.ResultHandler;
import org.briarproject.api.blogs.Blog;
import org.briarproject.api.blogs.BlogManager;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.NoSuchGroupException;
import org.briarproject.api.db.NoSuchMessageException;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.identity.Author;
import org.briarproject.api.identity.IdentityManager;
import org.briarproject.api.lifecycle.LifecycleManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class FeedControllerImpl extends BaseControllerImpl
		implements FeedController {

	private static final Logger LOG =
			Logger.getLogger(FeedControllerImpl.class.getName());

	@Inject
	FeedControllerImpl(@BackgroundExecutor Executor bgExecutor,
			LifecycleManager lifecycleManager, EventBus eventBus,
			AndroidNotificationManager notificationManager,
			IdentityManager identityManager, BlogManager blogManager) {
		super(bgExecutor, lifecycleManager, eventBus, notificationManager,
				identityManager, blogManager);
	}

	@Override
	public void onStart() {
		super.onStart();
		notificationManager.blockAllBlogPostNotifications();
		notificationManager.clearAllBlogPostNotifications();
	}

	@Override
	public void onStop() {
		super.onStop();
		notificationManager.unblockAllBlogPostNotifications();
	}

	@Override
	public void loadBlogPosts(
			final ResultExceptionHandler<Collection<BlogPostItem>, DbException> handler) {
		LOG.info("Loading all blog posts...");
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					// load blog posts
					long now = System.currentTimeMillis();
					Collection<BlogPostItem> posts = new ArrayList<>();
					for (Blog b : blogManager.getBlogs()) {
						try {
							posts.addAll(loadItems(b.getId()));
						} catch (NoSuchGroupException | NoSuchMessageException e) {
							if (LOG.isLoggable(WARNING))
								LOG.log(WARNING, e.toString(), e);
						}
					}
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Loading all posts took " + duration + " ms");
					handler.onResult(posts);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					handler.onException(e);
				}
			}
		});
	}

	@Override
	public void loadPersonalBlog(final ResultHandler<Blog> resultHandler) {
		LOG.info("Loading personal blog...");
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					// load blog posts
					long now = System.currentTimeMillis();
					Author a = identityManager.getLocalAuthor();
					Blog b = blogManager.getPersonalBlog(a);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Loading pers. blog took " + duration + " ms");
					resultHandler.onResult(b);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					resultHandler.onResult(null);
				}
			}
		});
	}

}
