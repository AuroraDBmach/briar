package org.briarproject.android.contact;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.ActionMenuView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.R;
import org.briarproject.android.ActivityComponent;
import org.briarproject.android.BriarActivity;
import org.briarproject.android.api.AndroidNotificationManager;
import org.briarproject.android.contact.ConversationAdapter.IntroductionHandler;
import org.briarproject.android.introduction.IntroductionActivity;
import org.briarproject.android.view.BriarRecyclerView;
import org.briarproject.android.view.TextInputView;
import org.briarproject.android.view.TextInputView.TextInputListener;
import org.briarproject.api.FormatException;
import org.briarproject.api.blogs.BlogSharingManager;
import org.briarproject.api.clients.SessionId;
import org.briarproject.api.contact.Contact;
import org.briarproject.api.contact.ContactId;
import org.briarproject.api.contact.ContactManager;
import org.briarproject.api.crypto.CryptoExecutor;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.NoSuchContactException;
import org.briarproject.api.db.NoSuchMessageException;
import org.briarproject.api.event.ContactConnectedEvent;
import org.briarproject.api.event.ContactDisconnectedEvent;
import org.briarproject.api.event.ContactRemovedEvent;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.IntroductionRequestReceivedEvent;
import org.briarproject.api.event.IntroductionResponseReceivedEvent;
import org.briarproject.api.event.InvitationReceivedEvent;
import org.briarproject.api.event.InvitationResponseReceivedEvent;
import org.briarproject.api.event.MessagesAckedEvent;
import org.briarproject.api.event.MessagesSentEvent;
import org.briarproject.api.event.PrivateMessageReceivedEvent;
import org.briarproject.api.forum.ForumSharingManager;
import org.briarproject.api.introduction.IntroductionManager;
import org.briarproject.api.introduction.IntroductionMessage;
import org.briarproject.api.introduction.IntroductionRequest;
import org.briarproject.api.introduction.IntroductionResponse;
import org.briarproject.api.messaging.MessagingManager;
import org.briarproject.api.messaging.PrivateMessage;
import org.briarproject.api.messaging.PrivateMessageFactory;
import org.briarproject.api.messaging.PrivateMessageHeader;
import org.briarproject.api.plugins.ConnectionRegistry;
import org.briarproject.api.settings.Settings;
import org.briarproject.api.settings.SettingsManager;
import org.briarproject.api.sharing.InvitationMessage;
import org.briarproject.api.sharing.InvitationRequest;
import org.briarproject.api.sharing.InvitationResponse;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;
import org.briarproject.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import de.hdodenhof.circleimageview.CircleImageView;
import im.delight.android.identicons.IdenticonDrawable;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt.OnHidePromptListener;

import static android.support.v4.app.ActivityOptionsCompat.makeCustomAnimation;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.contact.ConversationItem.IncomingItem;
import static org.briarproject.android.contact.ConversationItem.OutgoingItem;
import static org.briarproject.android.fragment.SettingsFragment.SETTINGS_NAMESPACE;

public class ConversationActivity extends BriarActivity
		implements EventListener, IntroductionHandler, TextInputListener {

	private static final Logger LOG =
			Logger.getLogger(ConversationActivity.class.getName());
	private static final int REQUEST_CODE_INTRODUCTION = 1;
	private static final String SHOW_ONBOARDING_INTRODUCTION =
			"showOnboardingIntroduction";

	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	ConnectionRegistry connectionRegistry;
	@Inject
	@CryptoExecutor
	Executor cryptoExecutor;

	private final Map<MessageId, byte[]> bodyCache = new ConcurrentHashMap<>();

	private ConversationAdapter adapter;
	private Toolbar toolbar;
	private CircleImageView toolbarAvatar;
	private ImageView toolbarStatus;
	private TextView toolbarTitle;
	private BriarRecyclerView list;
	private TextInputView textInputView;

	// Fields that are accessed from background threads must be volatile
	@Inject
	protected volatile ContactManager contactManager;
	@Inject
	protected volatile MessagingManager messagingManager;
	@Inject
	protected volatile EventBus eventBus;
	@Inject
	protected volatile SettingsManager settingsManager;
	@Inject
	volatile PrivateMessageFactory privateMessageFactory;
	@Inject
	protected volatile IntroductionManager introductionManager;
	@Inject
	protected volatile ForumSharingManager forumSharingManager;
	@Inject
	protected volatile BlogSharingManager blogSharingManager;

	private volatile GroupId groupId = null;
	private volatile ContactId contactId = null;
	private volatile String contactName = null;
	private volatile byte[] contactIdenticonKey = null;
	private volatile boolean connected = false;

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra("briar.GROUP_ID");
		if (b == null) throw new IllegalStateException();
		groupId = new GroupId(b);

		setContentView(R.layout.activity_conversation);

		// Custom Toolbar
		toolbar = (Toolbar) findViewById(R.id.toolbar);
		if (toolbar != null) {
			toolbarAvatar =
					(CircleImageView) toolbar.findViewById(R.id.contactAvatar);
			toolbarStatus =
					(ImageView) toolbar.findViewById(R.id.contactStatus);
			toolbarTitle = (TextView) toolbar.findViewById(R.id.contactName);
			setSupportActionBar(toolbar);
		}
		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayShowHomeEnabled(true);
			ab.setDisplayHomeAsUpEnabled(true);
			ab.setDisplayShowCustomEnabled(true);
			ab.setDisplayShowTitleEnabled(false);
		}

		String hexGroupId = StringUtils.toHexString(b);
		ViewCompat.setTransitionName(toolbarAvatar, "avatar" + hexGroupId);
		ViewCompat.setTransitionName(toolbarStatus, "bulb" + hexGroupId);

		adapter = new ConversationAdapter(this, this);
		list = (BriarRecyclerView) findViewById(R.id.conversationView);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.no_private_messages));

		textInputView = (TextInputView) findViewById(R.id.text_input_container);
		textInputView.setListener(this);
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);

		if (request == REQUEST_CODE_INTRODUCTION && result == RESULT_OK) {
			Snackbar snackbar = Snackbar.make(list, R.string.introduction_sent,
					Snackbar.LENGTH_SHORT);
			snackbar.getView().setBackgroundResource(R.color.briar_primary);
			snackbar.show();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		eventBus.addListener(this);
		notificationManager.blockNotification(groupId);
		notificationManager.clearPrivateMessageNotification(groupId);
		loadData();
		list.startPeriodicUpdate();
	}

	@Override
	public void onPause() {
		super.onPause();
		eventBus.removeListener(this);
		notificationManager.unblockNotification(groupId);
		list.stopPeriodicUpdate();
		if (isFinishing()) markMessagesRead();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu items for use in the action bar
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.conversation_actions, menu);

		enableIntroductionActionIfAvailable(
				menu.findItem(R.id.action_introduction));

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		// Handle presses on the action bar items
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				return true;
			case R.id.action_introduction:
				if (contactId == null) return false;
				Intent intent = new Intent(this, IntroductionActivity.class);
				intent.putExtra(IntroductionActivity.CONTACT_ID,
						contactId.getInt());
				ActivityOptionsCompat options =
						makeCustomAnimation(this, android.R.anim.slide_in_left,
								android.R.anim.slide_out_right);
				ActivityCompat.startActivityForResult(this, intent,
						REQUEST_CODE_INTRODUCTION, options.toBundle());
				return true;
			case R.id.action_social_remove_person:
				askToRemoveContact();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onBackPressed() {
		// FIXME disabled exit transition, because it doesn't work for some reason #318
		//supportFinishAfterTransition();
		finish();
	}

	private void loadData() {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (contactId == null)
						contactId = messagingManager.getContactId(groupId);
					if (contactName == null || contactIdenticonKey == null) {
						Contact contact = contactManager.getContact(contactId);
						contactName = contact.getAuthor().getName();
						contactIdenticonKey =
								contact.getAuthor().getId().getBytes();
					}
					connected = connectionRegistry.isConnected(contactId);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Loading contact took " + duration + " ms");
					displayContactDetails();
					// Load the messages here to make sure we have a contactId
					loadMessages();
				} catch (NoSuchContactException e) {
					finishOnUiThread();
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void displayContactDetails() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				toolbarAvatar.setImageDrawable(
						new IdenticonDrawable(contactIdenticonKey));
				toolbarTitle.setText(contactName);

				if (connected) {
					toolbarStatus.setImageDrawable(ContextCompat
							.getDrawable(ConversationActivity.this,
									R.drawable.contact_online));
					toolbarStatus
							.setContentDescription(getString(R.string.online));
				} else {
					toolbarStatus.setImageDrawable(ContextCompat
							.getDrawable(ConversationActivity.this,
									R.drawable.contact_offline));
					toolbarStatus
							.setContentDescription(getString(R.string.offline));
				}
				adapter.setContactName(contactName);
			}
		});
	}

	private void loadMessages() {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (contactId == null)
						contactId = messagingManager.getContactId(groupId);
					Collection<PrivateMessageHeader> headers =
							messagingManager.getMessageHeaders(contactId);
					Collection<IntroductionMessage> introductions =
							introductionManager
									.getIntroductionMessages(contactId);
					Collection<InvitationMessage> forumInvitations =
							forumSharingManager
									.getInvitationMessages(contactId);
					Collection<InvitationMessage> blogInvitations =
							blogSharingManager
									.getInvitationMessages(contactId);
					List<InvitationMessage> invitations = new ArrayList<>(
							forumInvitations.size() + blogInvitations.size());
					invitations.addAll(forumInvitations);
					invitations.addAll(blogInvitations);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Loading headers took " + duration + " ms");
					displayMessages(headers, introductions, invitations);
				} catch (NoSuchContactException e) {
					finishOnUiThread();
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void displayMessages(final Collection<PrivateMessageHeader> headers,
			final Collection<IntroductionMessage> introductions,
			final Collection<InvitationMessage> invitations) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				textInputView.setSendButtonEnabled(true);
				if (headers.isEmpty() && introductions.isEmpty() &&
						invitations.isEmpty()) {
					// we have no messages,
					// so let the list know to hide progress bar
					list.showData();
				} else {
					List<ConversationItem> items = new ArrayList<>();
					for (PrivateMessageHeader h : headers) {
						ConversationMessageItem item = ConversationItem.from(h);
						byte[] body = bodyCache.get(h.getId());
						if (body == null) loadMessageBody(h.getId());
						else item.setBody(body);
						items.add(item);
					}
					for (IntroductionMessage m : introductions) {
						ConversationItem item;
						if (m instanceof IntroductionRequest) {
							item = ConversationItem
									.from((IntroductionRequest) m);
						} else {
							item = ConversationItem
									.from(ConversationActivity.this,
											contactName,
											(IntroductionResponse) m);
						}
						items.add(item);
					}
					for (InvitationMessage i : invitations) {
						if (i instanceof InvitationRequest) {
							InvitationRequest r = (InvitationRequest) i;
							items.add(ConversationItem.from(r));
						} else if (i instanceof InvitationResponse) {
							InvitationResponse r = (InvitationResponse) i;
							items.add(ConversationItem
									.from(ConversationActivity.this,
											contactName, r));
						}
					}
					adapter.addAll(items);
					// Scroll to the bottom
					list.scrollToPosition(adapter.getItemCount() - 1);
				}
			}
		});
	}

	private void loadMessageBody(final MessageId m) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					byte[] body = messagingManager.getMessageBody(m);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Loading body took " + duration + " ms");
					displayMessageBody(m, body);
				} catch (NoSuchMessageException e) {
					// The item will be removed when we get the event
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void displayMessageBody(final MessageId m, final byte[] body) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				bodyCache.put(m, body);
				SparseArray<ConversationMessageItem> messages =
						adapter.getPrivateMessages();
				for (int i = 0; i < messages.size(); i++) {
					ConversationMessageItem item = messages.valueAt(i);
					if (item.getId().equals(m)) {
						item.setBody(body);
						adapter.notifyItemChanged(messages.keyAt(i));
						list.scrollToPosition(adapter.getItemCount() - 1);
						return;
					}
				}
			}
		});
	}

	private void addConversationItem(final ConversationItem item) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				adapter.add(item);
				// Scroll to the bottom
				list.scrollToPosition(adapter.getItemCount() - 1);
			}
		});
	}

	private void markMessagesRead() {
		List<MessageId> unread = new ArrayList<>();
		SparseArray<IncomingItem> list = adapter.getIncomingMessages();
		for (int i = 0; i < list.size(); i++) {
			IncomingItem item = list.valueAt(i);
			if (!item.isRead()) unread.add(item.getId());
		}
		if (unread.isEmpty()) return;
		if (LOG.isLoggable(INFO))
			LOG.info("Marking " + unread.size() + " messages read");
		markMessagesRead(Collections.unmodifiableList(unread));
	}

	private void markMessagesRead(final Collection<MessageId> unread) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					for (MessageId m : unread)
						// not really clean, but the messaging manager can
						// handle introduction messages as well
						messagingManager.setReadFlag(groupId, m, true);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Marking read took " + duration + " ms");
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact removed");
				finishOnUiThread();
			}
		} else if (e instanceof PrivateMessageReceivedEvent) {
			PrivateMessageReceivedEvent p = (PrivateMessageReceivedEvent) e;
			if (p.getGroupId().equals(groupId)) {
				LOG.info("Message received, adding");
				PrivateMessageHeader h = p.getMessageHeader();
				addConversationItem(ConversationItem.from(h));
				loadMessageBody(h.getId());
				markMessageReadIfNew(h);
			}
		} else if (e instanceof MessagesSentEvent) {
			MessagesSentEvent m = (MessagesSentEvent) e;
			if (m.getContactId().equals(contactId)) {
				LOG.info("Messages sent");
				markMessages(m.getMessageIds(), true, false);
			}
		} else if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent m = (MessagesAckedEvent) e;
			if (m.getContactId().equals(contactId)) {
				LOG.info("Messages acked");
				markMessages(m.getMessageIds(), true, true);
			}
		} else if (e instanceof ContactConnectedEvent) {
			ContactConnectedEvent c = (ContactConnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact connected");
				connected = true;
				displayContactDetails();
			}
		} else if (e instanceof ContactDisconnectedEvent) {
			ContactDisconnectedEvent c = (ContactDisconnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact disconnected");
				connected = false;
				displayContactDetails();
			}
		} else if (e instanceof IntroductionRequestReceivedEvent) {
			IntroductionRequestReceivedEvent event =
					(IntroductionRequestReceivedEvent) e;
			if (event.getContactId().equals(contactId)) {
				LOG.info("Introduction request received, adding...");
				IntroductionRequest ir = event.getIntroductionRequest();
				ConversationItem item = new ConversationIntroductionInItem(ir);
				addConversationItem(item);
			}
		} else if (e instanceof IntroductionResponseReceivedEvent) {
			IntroductionResponseReceivedEvent event =
					(IntroductionResponseReceivedEvent) e;
			if (event.getContactId().equals(contactId)) {
				LOG.info("Introduction response received, adding...");
				IntroductionResponse ir = event.getIntroductionResponse();
				ConversationItem item =
						ConversationItem.from(this, contactName, ir);
				addConversationItem(item);
			}
		} else if (e instanceof InvitationReceivedEvent) {
			InvitationReceivedEvent event =
					(InvitationReceivedEvent) e;
			if (event.getContactId().equals(contactId)) {
				LOG.info("Invitation received, reloading...");
				loadMessages();
			}
		} else if (e instanceof InvitationResponseReceivedEvent) {
			InvitationResponseReceivedEvent event =
					(InvitationResponseReceivedEvent) e;
			if (event.getContactId().equals(contactId)) {
				LOG.info("Invitation response received, reloading...");
				loadMessages();
			}
		}
	}

	private void markMessageReadIfNew(final PrivateMessageHeader h) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				ConversationItem item = adapter.getLastItem();
				if (item != null) {
					// Mark the message read if it's the newest message
					long lastMsgTime = item.getTime();
					long newMsgTime = h.getTimestamp();
					if (newMsgTime > lastMsgTime) markNewMessageRead(h.getId());
					else loadMessages();
				} else {
					// mark the message as read as well if it is the first one
					markNewMessageRead(h.getId());
				}
			}
		});
	}

	private void markNewMessageRead(final MessageId m) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					messagingManager.setReadFlag(groupId, m, true);
					loadMessages();
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void markMessages(final Collection<MessageId> messageIds,
			final boolean sent, final boolean seen) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Set<MessageId> messages = new HashSet<>(messageIds);
				SparseArray<OutgoingItem> list = adapter.getOutgoingMessages();
				for (int i = 0; i < list.size(); i++) {
					OutgoingItem item = list.valueAt(i);
					if (messages.contains(item.getId())) {
						item.setSent(sent);
						item.setSeen(seen);
						adapter.notifyItemChanged(list.keyAt(i));
					}
				}
			}
		});
	}

	@Override
	public void onSendClick(String text) {
		markMessagesRead();
		if (text.equals("")) return;
		long timestamp = System.currentTimeMillis();
		timestamp = Math.max(timestamp, getMinTimestampForNewMessage());
		createMessage(StringUtils.toUtf8(text), timestamp);
	}

	private long getMinTimestampForNewMessage() {
		// Don't use an earlier timestamp than the newest message
		ConversationItem item = adapter.getLastItem();
		return item == null ? 0 : item.getTime() + 1;
	}

	private void createMessage(final byte[] body, final long timestamp) {
		cryptoExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					storeMessage(privateMessageFactory.createPrivateMessage(
							groupId, timestamp, null, "text/plain", body),
							body);
				} catch (FormatException e) {
					throw new RuntimeException(e);
				}
			}
		});
	}

	private void storeMessage(final PrivateMessage m, final byte[] body) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					messagingManager.addLocalMessage(m);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Storing message took " + duration + " ms");

					MessageId id = m.getMessage().getId();
					PrivateMessageHeader h = new PrivateMessageHeader(id,
							m.getMessage().getTimestamp(), m.getContentType(),
							true, false, false, false);
					ConversationMessageItem item = ConversationItem.from(h);
					item.setBody(body);
					bodyCache.put(id, body);
					addConversationItem(item);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void askToRemoveContact() {
		DialogInterface.OnClickListener okListener =
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						removeContact();
					}
				};
		AlertDialog.Builder builder =
				new AlertDialog.Builder(ConversationActivity.this,
						R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.dialog_title_delete_contact));
		builder.setMessage(getString(R.string.dialog_message_delete_contact));
		builder.setPositiveButton(android.R.string.ok, okListener);
		builder.setNegativeButton(android.R.string.cancel, null);
		builder.show();
	}

	private void removeContact() {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					// make sure contactId is initialised
					if (contactId == null)
						contactId = messagingManager.getContactId(groupId);
					// remove contact with that ID
					contactManager.removeContact(contactId);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} finally {
					finishAfterContactRemoved();
				}
			}
		});
	}

	private void finishAfterContactRemoved() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				String deleted = getString(R.string.contact_deleted_toast);
				Toast.makeText(ConversationActivity.this, deleted, LENGTH_SHORT)
						.show();
				finish();
			}
		});
	}

	private void enableIntroductionActionIfAvailable(final MenuItem item) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					if (contactManager.getActiveContacts().size() > 1) {
						enableIntroductionAction(item);
						Settings settings =
								settingsManager.getSettings(SETTINGS_NAMESPACE);
						if (settings.getBoolean(SHOW_ONBOARDING_INTRODUCTION,
								true)) {
							showIntroductionOnboarding();
						}
					}
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void enableIntroductionAction(final MenuItem item) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				item.setEnabled(true);
			}
		});
	}

	private void showIntroductionOnboarding() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// find view of overflow icon
				View target = null;
				for (int i = 0; i < toolbar.getChildCount(); i++) {
					if (toolbar.getChildAt(i) instanceof ActionMenuView) {
						ActionMenuView menu =
								(ActionMenuView) toolbar.getChildAt(i);
						target = menu.getChildAt(menu.getChildCount() - 1);
						break;
					}
				}
				if (target == null) {
					LOG.warning("No Overflow Icon found!");
					return;
				}

				OnHidePromptListener listener = new OnHidePromptListener() {
					@Override
					public void onHidePrompt(MotionEvent motionEvent,
							boolean focalClicked) {
						introductionOnboardingSeen();
					}

					@Override
					public void onHidePromptComplete() {
					}
				};
				new MaterialTapTargetPrompt.Builder(ConversationActivity.this)
						.setTarget(target)
						.setPrimaryText(R.string.introduction_onboarding_title)
						.setSecondaryText(R.string.introduction_onboarding_text)
						.setBackgroundColourFromRes(R.color.briar_primary)
						.setIcon(R.drawable.ic_more_vert_accent)
						.setOnHidePromptListener(listener)
						.show();
			}
		});
	}

	private void introductionOnboardingSeen() {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					Settings settings = new Settings();
					settings.putBoolean(SHOW_ONBOARDING_INTRODUCTION, false);
					settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	@Override
	public void respondToIntroduction(final SessionId sessionId,
			final boolean accept) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				long timestamp = System.currentTimeMillis();
				timestamp = Math.max(timestamp, getMinTimestampForNewMessage());
				try {
					if (accept) {
						introductionManager
								.acceptIntroduction(contactId, sessionId,
										timestamp);
					} else {
						introductionManager
								.declineIntroduction(contactId, sessionId,
										timestamp);
					}
					loadMessages();
				} catch (DbException | FormatException e) {
					introductionResponseError();
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}

			}
		});
	}

	private void introductionResponseError() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(ConversationActivity.this,
						R.string.introduction_response_error,
						Toast.LENGTH_SHORT).show();
			}
		});
	}

}
