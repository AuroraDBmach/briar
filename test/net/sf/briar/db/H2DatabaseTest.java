package net.sf.briar.db;

import java.io.File;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;
import net.sf.briar.TestUtils;
import net.sf.briar.api.ContactId;
import net.sf.briar.api.Rating;
import net.sf.briar.api.crypto.Password;
import net.sf.briar.api.db.DbException;
import net.sf.briar.api.db.Status;
import net.sf.briar.api.protocol.AuthorId;
import net.sf.briar.api.protocol.BatchId;
import net.sf.briar.api.protocol.BundleId;
import net.sf.briar.api.protocol.GroupId;
import net.sf.briar.api.protocol.Message;
import net.sf.briar.api.protocol.MessageFactory;
import net.sf.briar.api.protocol.MessageId;
import net.sf.briar.protocol.MessageImpl;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class H2DatabaseTest extends TestCase {

	private static final int ONE_MEGABYTE = 1024 * 1024;
	private static final int MAX_SIZE = 5 * ONE_MEGABYTE;

	private final File testDir = TestUtils.getTestDirectory();
	// The password has the format <file password> <space> <user password>
	private final String passwordString = "foo bar";
	private final Random random = new Random();
	private final AuthorId authorId;
	private final BatchId batchId;
	private final ContactId contactId;
	private final GroupId groupId;
	private final MessageId messageId;
	private final long timestamp;
	private final int size;
	private final byte[] body;
	private final Message message;

	public H2DatabaseTest() {
		super();
		authorId = new AuthorId(TestUtils.getRandomId());
		batchId = new BatchId(TestUtils.getRandomId());
		contactId = new ContactId(1);
		groupId = new GroupId(TestUtils.getRandomId());
		messageId = new MessageId(TestUtils.getRandomId());
		timestamp = System.currentTimeMillis();
		size = 1234;
		body = new byte[size];
		random.nextBytes(body);
		message = new MessageImpl(messageId, MessageId.NONE, groupId, authorId,
				timestamp, body);
	}

	@Before
	public void setUp() {
		testDir.mkdirs();
	}

	@Test
	public void testPersistence() throws DbException {
		MessageFactory messageFactory = new TestMessageFactory();

		// Create a new database
		Database<Connection> db = open(false, messageFactory);
		// Store some records
		Connection txn = db.startTransaction();
		assertFalse(db.containsContact(txn, contactId));
		Map<String, String> transports = Collections.singletonMap("foo", "bar");
		assertEquals(contactId, db.addContact(txn, transports));
		assertTrue(db.containsContact(txn, contactId));
		assertFalse(db.containsSubscription(txn, groupId));
		db.addSubscription(txn, groupId);
		assertTrue(db.containsSubscription(txn, groupId));
		assertFalse(db.containsMessage(txn, messageId));
		db.addMessage(txn, message);
		assertTrue(db.containsMessage(txn, messageId));
		db.commitTransaction(txn);
		db.close();

		// Reopen the database
		db = open(true, messageFactory);
		// Check that the records are still there
		txn = db.startTransaction();
		assertTrue(db.containsContact(txn, contactId));
		transports = db.getTransports(txn, contactId);
		assertEquals(Collections.singletonMap("foo", "bar"), transports);
		assertTrue(db.containsSubscription(txn, groupId));
		assertTrue(db.containsMessage(txn, messageId));
		Message m1 = db.getMessage(txn, messageId);
		assertEquals(messageId, m1.getId());
		assertEquals(MessageId.NONE, m1.getParent());
		assertEquals(groupId, m1.getGroup());
		assertEquals(authorId, m1.getAuthor());
		assertEquals(timestamp, m1.getTimestamp());
		assertEquals(size, m1.getSize());
		assertTrue(Arrays.equals(body, m1.getBytes()));
		// Delete the records
		db.removeContact(txn, contactId);
		db.removeMessage(txn, messageId);
		db.removeSubscription(txn, groupId);
		db.commitTransaction(txn);
		db.close();

		// Repoen the database
		db = open(true, messageFactory);
		// Check that the records are gone
		txn = db.startTransaction();
		assertFalse(db.containsContact(txn, contactId));
		assertEquals(Collections.emptyMap(), db.getTransports(txn, contactId));
		assertFalse(db.containsSubscription(txn, groupId));
		assertFalse(db.containsMessage(txn, messageId));
		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testContactIdsIncrease() throws DbException {
		ContactId contactId1 = new ContactId(2);
		ContactId contactId2 = new ContactId(3);
		ContactId contactId3 = new ContactId(4);
		MessageFactory messageFactory = new TestMessageFactory();
		Database<Connection> db = open(false, messageFactory);

		// Create three contacts
		Connection txn = db.startTransaction();
		assertFalse(db.containsContact(txn, contactId));
		assertEquals(contactId, db.addContact(txn, null));
		assertTrue(db.containsContact(txn, contactId));
		assertFalse(db.containsContact(txn, contactId1));
		assertEquals(contactId1, db.addContact(txn, null));
		assertTrue(db.containsContact(txn, contactId1));
		assertFalse(db.containsContact(txn, contactId2));
		assertEquals(contactId2, db.addContact(txn, null));
		assertTrue(db.containsContact(txn, contactId2));
		// Delete one of the contacts
		db.removeContact(txn, contactId1);
		assertFalse(db.containsContact(txn, contactId1));
		// Add another contact - a new ID should be created
		assertFalse(db.containsContact(txn, contactId3));
		assertEquals(contactId3, db.addContact(txn, null));
		assertTrue(db.containsContact(txn, contactId3));
		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void testRatings() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);
		Connection txn = db.startTransaction();
		// Unknown authors should be unrated
		assertEquals(Rating.UNRATED, db.getRating(txn, authorId));
		// Store a rating
		db.setRating(txn, authorId, Rating.GOOD);
		db.commitTransaction(txn);
		// Check that the rating was stored
		txn = db.startTransaction();
		assertEquals(Rating.GOOD, db.getRating(txn, authorId));
		db.commitTransaction(txn);
		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testUnsubscribingRemovesMessage() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Subscribe to a group and store a message
		Connection txn = db.startTransaction();
		db.addSubscription(txn, groupId);
		db.addMessage(txn, message);
		db.commitTransaction(txn);

		// Unsubscribing from the group should delete the message
		txn = db.startTransaction();
		assertTrue(db.containsMessage(txn, messageId));
		db.removeSubscription(txn, groupId);
		assertFalse(db.containsMessage(txn, messageId));
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testSendableMessagesMustBeSendable() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, contactId, groupId);
		db.addMessage(txn, message);
		db.setStatus(txn, contactId, messageId, Status.NEW);
		db.commitTransaction(txn);

		// The message should not be sendable
		txn = db.startTransaction();
		assertEquals(0, db.getSendability(txn, messageId));
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// Changing the sendability to > 0 should make the message sendable
		txn = db.startTransaction();
		db.setSendability(txn, messageId, 1);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		db.commitTransaction(txn);

		// Changing the sendability to 0 should make the message unsendable
		txn = db.startTransaction();
		db.setSendability(txn, messageId, 0);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testSendableMessagesMustBeNew() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, contactId, groupId);
		db.addMessage(txn, message);
		db.setSendability(txn, messageId, 1);
		db.commitTransaction(txn);

		// The message has no status yet, so it should not be sendable
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// Changing the status to Status.NEW should make the message sendable
		txn = db.startTransaction();
		db.setStatus(txn, contactId, messageId, Status.NEW);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		db.commitTransaction(txn);

		// Changing the status to SENT should make the message unsendable
		txn = db.startTransaction();
		db.setStatus(txn, contactId, messageId, Status.SENT);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// Changing the status to SEEN should also make the message unsendable
		txn = db.startTransaction();
		db.setStatus(txn, contactId, messageId, Status.SEEN);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testSendableMessagesMustBeSubscribed() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addMessage(txn, message);
		db.setSendability(txn, messageId, 1);
		db.setStatus(txn, contactId, messageId, Status.NEW);
		db.commitTransaction(txn);

		// The contact is not subscribed, so the message should not be sendable
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// The contact subscribing should make the message sendable
		txn = db.startTransaction();
		db.addSubscription(txn, contactId, groupId);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		db.commitTransaction(txn);

		// The contact unsubscribing should make the message unsendable
		txn = db.startTransaction();
		db.clearSubscriptions(txn, contactId);
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testSendableMessagesMustFitCapacity() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, contactId, groupId);
		db.addMessage(txn, message);
		db.setSendability(txn, messageId, 1);
		db.setStatus(txn, contactId, messageId, Status.NEW);
		db.commitTransaction(txn);

		// The message is too large to send
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, size - 1).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// The message is just the right size to send
		txn = db.startTransaction();
		it = db.getSendableMessages(txn, contactId, size).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testBatchesToAck() throws DbException {
		BatchId batchId1 = new BatchId(TestUtils.getRandomId());
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact and some batches to ack
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addBatchToAck(txn, contactId, batchId);
		db.addBatchToAck(txn, contactId, batchId1);
		db.commitTransaction(txn);

		// Both batch IDs should be returned
		txn = db.startTransaction();
		Set<BatchId> acks = db.removeBatchesToAck(txn, contactId);
		assertEquals(2, acks.size());
		assertTrue(acks.contains(batchId));
		assertTrue(acks.contains(batchId1));
		db.commitTransaction(txn);

		// Both batch IDs should have been removed
		txn = db.startTransaction();
		acks = db.removeBatchesToAck(txn, contactId);
		assertEquals(0, acks.size());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testRemoveAckedBatch() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, contactId, groupId);
		db.addMessage(txn, message);
		db.setSendability(txn, messageId, 1);
		db.setStatus(txn, contactId, messageId, Status.NEW);
		db.commitTransaction(txn);

		// Get the message and mark it as sent
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		assertFalse(it.hasNext());
		db.setStatus(txn, contactId, messageId, Status.SENT);
		db.addOutstandingBatch(txn, contactId, batchId,
				Collections.singleton(messageId));
		db.commitTransaction(txn);

		// The message should no longer be sendable
		txn = db.startTransaction();
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		// Pretend that the batch was acked
		db.removeAckedBatch(txn, contactId, batchId);
		// The message still should not be sendable
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testRemoveLostBatch() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact, subscribe to a group and store a message
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, contactId, groupId);
		db.addMessage(txn, message);
		db.setSendability(txn, messageId, 1);
		db.setStatus(txn, contactId, messageId, Status.NEW);
		db.commitTransaction(txn);

		// Get the message and mark it as sent
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		assertFalse(it.hasNext());
		db.setStatus(txn, contactId, messageId, Status.SENT);
		db.addOutstandingBatch(txn, contactId, batchId,
				Collections.singleton(messageId));
		db.commitTransaction(txn);

		// The message should no longer be sendable
		txn = db.startTransaction();
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertFalse(it.hasNext());
		// Pretend that the batch was lost
		db.removeLostBatch(txn, contactId, batchId);
		// The message should be sendable again
		it = db.getSendableMessages(txn, contactId, ONE_MEGABYTE).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testRetransmission() throws DbException {
		BundleId bundleId = new BundleId(TestUtils.getRandomId());
		BundleId bundleId1 = new BundleId(TestUtils.getRandomId());
		BundleId bundleId2 = new BundleId(TestUtils.getRandomId());
		BundleId bundleId3 = new BundleId(TestUtils.getRandomId());
		BundleId bundleId4 = new BundleId(TestUtils.getRandomId());
		BatchId batchId1 = new BatchId(TestUtils.getRandomId());
		BatchId batchId2 = new BatchId(TestUtils.getRandomId());
		Set<MessageId> empty = Collections.emptySet();
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact
		Connection txn = db.startTransaction();
		assertEquals(contactId, db.addContact(txn, null));
		// Add an oustanding batch (associated with BundleId.NONE)
		db.addOutstandingBatch(txn, contactId, batchId, empty);
		// Receive a bundle
		Set<BatchId> lost = db.addReceivedBundle(txn, contactId, bundleId);
		assertTrue(lost.isEmpty());
		// Add a couple more outstanding batches (associated with bundleId)
		db.addOutstandingBatch(txn, contactId, batchId1, empty);
		db.addOutstandingBatch(txn, contactId, batchId2, empty);
		// Receive another bundle
		lost = db.addReceivedBundle(txn, contactId, bundleId1);
		assertTrue(lost.isEmpty());
		// The contact acks one of the batches - it should not be retransmitted
		db.removeAckedBatch(txn, contactId, batchId1);
		// Receive another bundle - batchId should now be considered lost
		lost = db.addReceivedBundle(txn, contactId, bundleId2);
		assertEquals(1, lost.size());
		assertTrue(lost.contains(batchId));
		// Receive another bundle - batchId2 should now be considered lost
		lost = db.addReceivedBundle(txn, contactId, bundleId3);
		assertEquals(1, lost.size());
		assertTrue(lost.contains(batchId2));
		// Receive another bundle - no further losses
		lost = db.addReceivedBundle(txn, contactId, bundleId4);
		assertTrue(lost.isEmpty());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testGetMessagesByAuthor() throws DbException {
		AuthorId authorId1 = new AuthorId(TestUtils.getRandomId());
		MessageId messageId1 = new MessageId(TestUtils.getRandomId());
		Message message1 = new MessageImpl(messageId1, MessageId.NONE, groupId,
				authorId1, timestamp, body);
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Subscribe to a group and store two messages
		Connection txn = db.startTransaction();
		db.addSubscription(txn, groupId);
		db.addMessage(txn, message);
		db.addMessage(txn, message1);
		db.commitTransaction(txn);

		// Check that each message is retrievable via its author
		txn = db.startTransaction();
		Iterator<MessageId> it =
			db.getMessagesByAuthor(txn, authorId).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		assertFalse(it.hasNext());
		it = db.getMessagesByAuthor(txn, authorId1).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId1, it.next());
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testGetNumberOfSendableChildren() throws DbException {
		MessageId childId1 = new MessageId(TestUtils.getRandomId());
		MessageId childId2 = new MessageId(TestUtils.getRandomId());
		MessageId childId3 = new MessageId(TestUtils.getRandomId());
		GroupId groupId1 = new GroupId(TestUtils.getRandomId());
		Message child1 = new MessageImpl(childId1, messageId, groupId,
				authorId, timestamp, body);
		Message child2 = new MessageImpl(childId2, messageId, groupId,
				authorId, timestamp, body);
		// The third child is in a different group
		Message child3 = new MessageImpl(childId3, messageId, groupId1,
				authorId, timestamp, body);
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Subscribe to the groups and store the messages
		Connection txn = db.startTransaction();
		db.addSubscription(txn, groupId);
		db.addSubscription(txn, groupId1);
		db.addMessage(txn, message);
		db.addMessage(txn, child1);
		db.addMessage(txn, child2);
		db.addMessage(txn, child3);
		// Make all the children sendable
		db.setSendability(txn, childId1, 1);
		db.setSendability(txn, childId2, 5);
		db.setSendability(txn, childId3, 3);
		db.commitTransaction(txn);

		// There should be two sendable children
		txn = db.startTransaction();
		assertEquals(2, db.getNumberOfSendableChildren(txn, messageId));
		// Make one of the children unsendable
		db.setSendability(txn, childId1, 0);
		// Now there should be one sendable child
		assertEquals(1, db.getNumberOfSendableChildren(txn, messageId));
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testGetOldMessages() throws DbException {
		MessageId messageId1 = new MessageId(TestUtils.getRandomId());
		Message message1 = new MessageImpl(messageId1, MessageId.NONE, groupId,
				authorId, timestamp + 1000, body);
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Subscribe to a group and store two messages
		Connection txn = db.startTransaction();
		db.addSubscription(txn, groupId);
		db.addMessage(txn, message);
		db.addMessage(txn, message1);
		db.commitTransaction(txn);

		// Allowing enough capacity for one message should return the older one
		txn = db.startTransaction();
		Iterator<MessageId> it = db.getOldMessages(txn, size).iterator();
		assertTrue(it.hasNext());
		assertEquals(messageId, it.next());
		assertFalse(it.hasNext());
		db.commitTransaction(txn);

		// Allowing enough capacity for both messages should return both
		txn = db.startTransaction();
		Set<MessageId> ids = new HashSet<MessageId>();
		for(MessageId id : db.getOldMessages(txn, size * 2)) ids.add(id);
		assertEquals(2, ids.size());
		assertTrue(ids.contains(messageId));
		assertTrue(ids.contains(messageId1));
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testGetFreeSpace() throws DbException {
		byte[] largeBody = new byte[ONE_MEGABYTE];
		for(int i = 0; i < largeBody.length; i++) largeBody[i] = (byte) i;
		Message message1 = new MessageImpl(messageId, MessageId.NONE, groupId,
				authorId, timestamp, largeBody);
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Sanity check: there should be enough space on disk for this test
		assertTrue(testDir.getFreeSpace() > MAX_SIZE);
		// The free space should not be more than the allowed maximum size
		long free = db.getFreeSpace();
		assertTrue(free <= MAX_SIZE);
		assertTrue(free > 0);
		// Storing a message should reduce the free space
		Connection txn = db.startTransaction();
		db.addSubscription(txn, groupId);
		db.addMessage(txn, message1);
		db.commitTransaction(txn);
		assertTrue(db.getFreeSpace() < free);

		db.close();
		context.assertIsSatisfied();
	}

	@Test
	public void testCloseWaitsForCommit() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		final AtomicBoolean transactionFinished = new AtomicBoolean(false);
		final AtomicBoolean closed = new AtomicBoolean(false);
		final AtomicBoolean error = new AtomicBoolean(false);
		final Database<Connection> db = open(false, messageFactory);

		// Start a transaction
		Connection txn = db.startTransaction();
		// In another thread, close the database
		Thread t = new Thread() {
			public void run() {
				try {
					db.close();
					closed.set(true);
					if(!transactionFinished.get()) error.set(true);
				} catch(DbException e) {
					error.set(true);
				}
			}
		};
		t.start();
		// Do whatever the transaction needs to do
		try {
			Thread.sleep(100);
		} catch(InterruptedException ignored) {}
		transactionFinished.set(true);
		// Commit the transaction
		db.commitTransaction(txn);
		// The other thread should now terminate
		try {
			t.join(10 * 1000);
		} catch(InterruptedException ignored) {}
		assertTrue(closed.get());
		// Check that the other thread didn't encounter an error
		assertFalse(error.get());
	}

	@Test
	public void testCloseWaitsForAbort() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		final AtomicBoolean transactionFinished = new AtomicBoolean(false);
		final AtomicBoolean closed = new AtomicBoolean(false);
		final AtomicBoolean error = new AtomicBoolean(false);
		final Database<Connection> db = open(false, messageFactory);

		// Start a transaction
		Connection txn = db.startTransaction();
		// In another thread, close the database
		Thread t = new Thread() {
			public void run() {
				try {
					db.close();
					closed.set(true);
					if(!transactionFinished.get()) error.set(true);
				} catch(DbException e) {
					error.set(true);
				}
			}
		};
		t.start();
		// Do whatever the transaction needs to do
		try {
			Thread.sleep(100);
		} catch(InterruptedException ignored) {}
		transactionFinished.set(true);
		// Abort the transaction
		db.abortTransaction(txn);
		// The other thread should now terminate
		try {
			t.join(10000);
		} catch(InterruptedException ignored) {}
		assertTrue(closed.get());
		// Check that the other thread didn't encounter an error
		assertFalse(error.get());
	}

	@Test
	public void testUpdateTransports() throws DbException {
		Mockery context = new Mockery();
		MessageFactory messageFactory = context.mock(MessageFactory.class);
		Database<Connection> db = open(false, messageFactory);

		// Add a contact with some transport details
		Connection txn = db.startTransaction();
		Map<String, String> transports = Collections.singletonMap("foo", "bar");
		assertEquals(contactId, db.addContact(txn, transports));
		assertEquals(transports, db.getTransports(txn, contactId));
		// Replace the transport details
		transports = new TreeMap<String, String>();
		transports.put("foo", "bar baz");
		transports.put("bar", "baz quux");
		db.setTransports(txn, contactId, transports);
		assertEquals(transports, db.getTransports(txn, contactId));
		// Remove the transport details
		db.setTransports(txn, contactId, null);
		assertEquals(Collections.emptyMap(), db.getTransports(txn, contactId));
		// Set the local transport details
		db.setTransports(txn, transports);
		assertEquals(transports, db.getTransports(txn));
		// Remove the local transport details
		db.setTransports(txn, null);
		assertEquals(Collections.emptyMap(), db.getTransports(txn));
		db.commitTransaction(txn);

		db.close();
		context.assertIsSatisfied();
	}

	private Database<Connection> open(boolean resume,
			MessageFactory messageFactory) throws DbException {
		final char[] passwordArray = passwordString.toCharArray();
		Mockery context = new Mockery();
		final Password password = context.mock(Password.class);
		context.checking(new Expectations() {{
			oneOf(password).getPassword();
			will(returnValue(passwordArray));
		}});
		Database<Connection> db =
			new H2Database(testDir, messageFactory, password, MAX_SIZE);
		db.open(resume);
		context.assertIsSatisfied();
		// The password array should be cleared after use
		assertTrue(Arrays.equals(new char[passwordString.length()],
				passwordArray));
		return db;
	}

	@After
	public void tearDown() {
		TestUtils.deleteTestDirectory(testDir);
	}

	private static class TestMessageFactory implements MessageFactory {

		public Message createMessage(MessageId id, MessageId parent,
				GroupId group, AuthorId author, long timestamp, byte[] raw) {
			return new MessageImpl(id, parent, group, author, timestamp, raw);
		}
	}
}
