package org.briarproject.android.contact;

import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;
import static java.text.DateFormat.SHORT;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP_1;
import static org.briarproject.android.util.CommonLayoutParams.WRAP_WRAP_1;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.briarproject.R;
import org.briarproject.android.util.HorizontalBorder;
import org.briarproject.android.util.ElasticHorizontalSpace;
import org.briarproject.android.util.LayoutUtils;
import org.briarproject.api.AuthorId;
import org.briarproject.api.android.DatabaseUiExecutor;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.NoSuchMessageException;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.messaging.GroupId;
import org.briarproject.api.messaging.MessageId;

import roboguice.activity.RoboActivity;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class ReadPrivateMessageActivity extends RoboActivity
implements OnClickListener {

	static final int RESULT_REPLY = RESULT_FIRST_USER;
	static final int RESULT_PREV = RESULT_FIRST_USER + 1;
	static final int RESULT_NEXT = RESULT_FIRST_USER + 2;

	private static final Logger LOG =
			Logger.getLogger(ReadPrivateMessageActivity.class.getName());

	private String contactName = null;
	private AuthorId localAuthorId = null;
	private boolean read = false;
	private ImageButton readButton = null, prevButton = null, nextButton = null;
	private ImageButton replyButton = null;
	private TextView content = null;

	// Fields that are accessed from background threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject @DatabaseUiExecutor private volatile Executor dbUiExecutor;
	@Inject private volatile LifecycleManager lifecycleManager;
	private volatile MessageId messageId = null;
	private volatile GroupId groupId = null;
	private volatile long timestamp = -1;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		contactName = i.getStringExtra("org.briarproject.CONTACT_NAME");
		if(contactName == null) throw new IllegalStateException();
		setTitle(contactName);
		byte[] b = i.getByteArrayExtra("org.briarproject.LOCAL_AUTHOR_ID");
		if(b == null) throw new IllegalStateException();
		localAuthorId = new AuthorId(b);
		String authorName = i.getStringExtra("org.briarproject.AUTHOR_NAME");
		if(authorName == null) throw new IllegalStateException();
		b = i.getByteArrayExtra("org.briarproject.MESSAGE_ID");
		if(b == null) throw new IllegalStateException();
		messageId = new MessageId(b);
		b = i.getByteArrayExtra("org.briarproject.GROUP_ID");
		if(b == null) throw new IllegalStateException();
		groupId = new GroupId(b);
		String contentType = i.getStringExtra("org.briarproject.CONTENT_TYPE");
		if(contentType == null) throw new IllegalStateException();
		timestamp = i.getLongExtra("org.briarproject.TIMESTAMP", -1);
		if(timestamp == -1) throw new IllegalStateException();

		if(state == null) {
			read = false;
			setReadInDatabase(true);
		} else {
			read = state.getBoolean("org.briarproject.READ");
		}

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_WRAP);
		layout.setOrientation(VERTICAL);

		ScrollView scrollView = new ScrollView(this);
		// Give me all the width and all the unused height
		scrollView.setLayoutParams(MATCH_WRAP_1);

		LinearLayout message = new LinearLayout(this);
		message.setOrientation(VERTICAL);
		Resources res = getResources();
		message.setBackgroundColor(res.getColor(R.color.content_background));

		LinearLayout header = new LinearLayout(this);
		header.setLayoutParams(MATCH_WRAP);
		header.setOrientation(HORIZONTAL);
		header.setGravity(CENTER_VERTICAL);

		int pad = LayoutUtils.getPadding(this);

		TextView name = new TextView(this);
		// Give me all the unused width
		name.setLayoutParams(WRAP_WRAP_1);
		name.setTextSize(18);
		name.setMaxLines(1);
		name.setPadding(pad, pad, pad, pad);
		name.setText(authorName);
		header.addView(name);

		TextView date = new TextView(this);
		date.setTextSize(14);
		date.setPadding(0, pad, pad, pad);
		long now = System.currentTimeMillis();
		date.setText(DateUtils.formatSameDayTime(timestamp, now, SHORT, SHORT));
		header.addView(date);
		message.addView(header);

		if(contentType.equals("text/plain")) {
			// Load and display the message body
			content = new TextView(this);
			content.setPadding(pad, 0, pad, pad);
			message.addView(content);
			loadMessageBody();
		}
		scrollView.addView(message);
		layout.addView(scrollView);

		layout.addView(new HorizontalBorder(this));

		LinearLayout footer = new LinearLayout(this);
		footer.setLayoutParams(MATCH_WRAP);
		footer.setOrientation(HORIZONTAL);
		footer.setGravity(CENTER);

		readButton = new ImageButton(this);
		readButton.setBackgroundResource(0);
		if(read) readButton.setImageResource(R.drawable.content_unread);
		else readButton.setImageResource(R.drawable.content_read);
		readButton.setOnClickListener(this);
		footer.addView(readButton);
		footer.addView(new ElasticHorizontalSpace(this));

		prevButton = new ImageButton(this);
		prevButton.setBackgroundResource(0);
		prevButton.setImageResource(R.drawable.navigation_previous_item);
		prevButton.setOnClickListener(this);
		footer.addView(prevButton);
		footer.addView(new ElasticHorizontalSpace(this));

		nextButton = new ImageButton(this);
		nextButton.setBackgroundResource(0);
		nextButton.setImageResource(R.drawable.navigation_next_item);
		nextButton.setOnClickListener(this);
		footer.addView(nextButton);
		footer.addView(new ElasticHorizontalSpace(this));

		replyButton = new ImageButton(this);
		replyButton.setBackgroundResource(0);
		replyButton.setImageResource(R.drawable.social_reply);
		replyButton.setOnClickListener(this);
		footer.addView(replyButton);
		layout.addView(footer);

		setContentView(layout);
	}

	private void setReadInDatabase(final boolean read) {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					db.setReadFlag(messageId, read);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Setting flag took " + duration + " ms");
					setReadInUi(read);
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void setReadInUi(final boolean read) {
		runOnUiThread(new Runnable() {
			public void run() {
				ReadPrivateMessageActivity.this.read = read;
				if(read) readButton.setImageResource(R.drawable.content_unread);
				else readButton.setImageResource(R.drawable.content_read);
			}
		});
	}

	private void loadMessageBody() {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					byte[] body = db.getMessageBody(messageId);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Loading message took " + duration + " ms");
					final String text = new String(body, "UTF-8");
					runOnUiThread(new Runnable() {
						public void run() {
							content.setText(text);
						}
					});
				} catch(NoSuchMessageException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Message removed");
					runOnUiThread(new Runnable() {
						public void run() {
							finish();
						}
					});
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				} catch(UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}
		});
	}

	@Override
	public void onSaveInstanceState(Bundle state) {
		super.onSaveInstanceState(state);
		state.putBoolean("org.briarproject.READ", read);
	}

	public void onClick(View view) {
		if(view == readButton) {
			setReadInDatabase(!read);
		} else if(view == prevButton) {
			setResult(RESULT_PREV);
			finish();
		} else if(view == nextButton) {
			setResult(RESULT_NEXT);
			finish();
		} else if(view == replyButton) {
			Intent i = new Intent(this, WritePrivateMessageActivity.class);
			i.putExtra("org.briarproject.CONTACT_NAME", contactName);
			i.putExtra("org.briarproject.GROUP_ID", groupId.getBytes());
			i.putExtra("org.briarproject.LOCAL_AUTHOR_ID",
					localAuthorId.getBytes());
			i.putExtra("org.briarproject.PARENT_ID", messageId.getBytes());
			i.putExtra("org.briarproject.TIMESTAMP", timestamp);
			startActivity(i);
			setResult(RESULT_REPLY);
			finish();
		}
	}
}
