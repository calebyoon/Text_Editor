package local.texteditor;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.umich.imlc.collabrify.client.*;
import edu.umich.imlc.collabrify.client.exceptions.CollabrifyException;
import local.texteditor.MovesProtos.Move;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.*;

public class MainActivity extends Activity {
	private final String TAG1 = "adds";
	private final String TAG2 = "dels";
	public final static String EXTRA_MESSAGE = "local.myfirstapp.message";
	private EditText to_broadcast;
	private String continuousString = "";
	private int continuousCount = 0;
	private long startTime;

	private static final Level LOGGING_LEVEL = Level.ALL;

	private CollabrifyClient myClient;
	private Button createSessionButton;
	private Button joinSessionButton;
	private Button leaveSessionButton;
	private CheckBox withBaseFile;
	private boolean baseFile = false;
	private CollabrifyListener collabrifyListener;
	private ArrayList<String> tags = new ArrayList<String>();
	private long sessionId;
	private String sessionName;
	private ByteArrayInputStream baseFileBuffer;
	private ByteArrayOutputStream baseFileReceiveBuffer;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		/*
		 * get the edittext and link user to edittext
		 */
		to_broadcast = (EditText) findViewById(R.id.to_broadcast);
		User.to_broadcast = to_broadcast;
		User.cursorList.put(User.Id, 0);

		/*
		 * timer thread
		 */
		new Thread() {
			public void run() {
				startTime = System.currentTimeMillis();
				while (true) {
					if (System.currentTimeMillis() - startTime >= 600
							&& continuousCount != 0)
						generateInsertDelete();
					else if (continuousCount == 0 && User.needToSynchronize)
						User.Synchronize();
				}
			}
		}.start();

		/*
		 * define undo/redo buttons
		 */
		Button undoButton = (Button) findViewById(R.id.UndoButton);
		Button redoButton = (Button) findViewById(R.id.RedoButton);
		undoButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (continuousCount != 0)
					generateInsertDelete();

				EditCom com = User.Undo(); // now broadcast retMove
				if (com != null) {
					Move retmove = com.generateMoveMes(1);
					sendretMove(retmove, "undo");
				}
			}
		});
		redoButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (continuousCount != 0)
					generateInsertDelete();

				EditCom com = User.Redo(); // now broadcast retMove
				if (com != null) {
					Move retmove = com.generateMoveMes(2);
					sendretMove(retmove, "redo");
				}
			}
		});

		/*
		 * define basic operation listener
		 */
		to_broadcast.setSingleLine(false);
		to_broadcast.setHorizontallyScrolling(false);
		to_broadcast.setLongClickable(false);
		to_broadcast.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (continuousCount != 0)
					generateInsertDelete();

				int cursorNewLoc = to_broadcast.getSelectionEnd();
				int offset = cursorNewLoc - User.cursorLoc;

				if (offset != 0) {
					to_broadcast.setSelection(cursorNewLoc);
					User.cursorLoc = cursorNewLoc;

					EditCom com = new EditCom(User.Operation.CURSOR, null,
							offset);
					User.undoList.add(com);
					Move retmove = com.generateMoveMes(0); // now broadcast
															// retmove
					sendretMove(retmove, "cur");

					User.redoList.clear();
				}
			}
		});

		to_broadcast.addTextChangedListener(new TextWatcher() {
			@Override
			public void afterTextChanged(Editable s) {
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
				if (User.isTextSetManually) {
					if (count > after) // delete
					{
						Log.i(TAG2, "sequence: " + s);
						Log.i(TAG2, "start: " + start);
						Log.i(TAG2, "count: " + count);
						Log.i(TAG2, "after: " + after);
						Log.i(TAG2, "characters deleted: "
								+ s.toString().substring(start, start + count));

						if (continuousCount > 0)
							generateInsertDelete();

						startTime = System.currentTimeMillis();
						continuousCount--;
						continuousString = s.toString().substring(start,
								start + count)
								+ continuousString;
					}
				}
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				if (User.isTextSetManually) {
					if (count < before) // this is a delete, deal with adding it
										// to the queue elsewhere
					{
					} else if (count > before) // this is an add
					{
						Log.i(TAG1, "sequence: " + s);
						Log.i(TAG1, "start: " + start);
						Log.i(TAG1, "before: " + before);
						Log.i(TAG1, "count: " + count);
						Log.i(TAG1, "characters added: "
								+ s.toString()
										.substring(start, (start + count)));

						if (continuousCount < 0)
							generateInsertDelete();

						startTime = System.currentTimeMillis();
						continuousCount++;
						continuousString += s.toString().substring(start,
								start + count);
					} else // this is a full replace
					{
					}
				} else {
					User.isTextSetManually = true;
				}
			}
		});

		
		
		/*
		 * createSession, joinSession, leaveSession, receiveEvent,
		 * broadcastEvetn
		 */
		withBaseFile = (CheckBox) findViewById(R.id.withBaseFileCheckBox);
		createSessionButton = (Button) findViewById(R.id.CreateButton);
		joinSessionButton = (Button) findViewById(R.id.JoinButton);
		leaveSessionButton = (Button) findViewById(R.id.LeaveButton);
		// enable logging
		Logger.getLogger("edu.umich.imlc.collabrify.client").setLevel(
				LOGGING_LEVEL);

		createSessionButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					Random rand = new Random();
					sessionName = "Test " + rand.nextInt(Integer.MAX_VALUE);

					if (withBaseFile.isChecked()) {
						baseFile = true;
						baseFileBuffer = new ByteArrayInputStream(to_broadcast
								.getText().toString().getBytes());
						myClient.createSessionWithBase(sessionName, tags, null,
								0);
					} else {
						myClient.createSession(sessionName, tags, null, 0);
					}
					Log.i("session", "Session name is " + sessionName);
				} catch (CollabrifyException e) {
					System.err.println("error " + e);
				}
			}
		});

		joinSessionButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				try {
					myClient.requestSessionList(tags); // UI maybe
				} catch (Exception e) {
					System.err.println("error " + e);
				}
			}
		});

		leaveSessionButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					if (myClient.inSession())
						myClient.leaveSession(true); // UI maybe
				} catch (CollabrifyException e) {
					System.err.println("error " + e);
				}
			}
		});

		collabrifyListener = new CollabrifyAdapter() {
			@Override
			public void onDisconnect() {
				Log.i("session", "disconnected");
				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						createSessionButton.setText("Create");
					}
				});
			}

			@Override
			public void onReceiveEvent(final long orderId, final int subId,
					String eventType, final byte[] data) {
				System.out.println("RECEIVED SUB ID:" + subId);
				Log.i("success", "received correctly " + eventType.toString());
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						try {
							Move latestMove = Move.parseFrom(data);
							int userWhoMadeMove = latestMove.getUserId();
							String moveData;
							int moveType = latestMove.getMoveType();
							int offsetValue = latestMove.getCursorChange();
							int undoValue = latestMove.getUndo();
							//int recMoveId = latestMove.getMoveId();

							if (!User.cursorList.containsKey(userWhoMadeMove)) // new user
							{
								User.cursorList.put(userWhoMadeMove, User.cursorList.get(User.Id) );
							}
							Log.i("success", "new user: " + userWhoMadeMove
									+ " move");

							Log.i("print", "UserID who made move: "
									+ userWhoMadeMove);		
							Log.i("print", "undo value: " + undoValue);
							Log.i("print", "offset value: " + offsetValue);

							// ---add----
							if (moveType == 1) {
								moveData = latestMove.getData();
								Log.i("print", "add");
								Log.i("print", "String added/deleted: "
										+ moveData);
								User.AddShadow(userWhoMadeMove, offsetValue,
										moveData);
							}
							// ---delete----
							else if (moveType == 2) {
								moveData = latestMove.getData();
								Log.i("print", "delete");
								Log.i("print", "String added/deleted: "
										+ moveData);
								User.DeleteShadow(userWhoMadeMove, offsetValue);
							}
							// ---cursorChange----
							else // should be moveType 3
							{
								Log.i("print", "cursorChange");
								User.CursorChangeShadow(userWhoMadeMove,
										offsetValue);
							}

							// if synchronize texteditor is needed
							if (userWhoMadeMove != User.Id || undoValue != 0)
							{
								User.numDiffMove++;
							}
							if (User.lastsubId == subId)
							{
								if (continuousCount == 0 && User.numDiffMove > 0) // if local user is not typign
								{
									User.Synchronize();
								}
								else if (User.numDiffMove > 0)// if local user is typing, sync later
								{								
									User.needToSynchronize = true;
								}
								else //nothing is different from shadow
								{
									User.lastsubId = -1;
								}
							}
							
						} 
						catch (InvalidProtocolBufferException e) {
							Log.i("failed", "bad parse attempt: " + e);
							e.printStackTrace();
						}
					}
				});
			}

			@Override
			public void onReceiveSessionList(
					final List<CollabrifySession> sessionList) {
				if (sessionList.isEmpty()) {
					Log.i("session", "No session available");
					return;
				}
				List<String> sessionNames = new ArrayList<String>();
				for (CollabrifySession s : sessionList) {
					sessionNames.add(s.name());
				}
				final AlertDialog.Builder builder = new AlertDialog.Builder(
						MainActivity.this);
				builder.setTitle("Choose Session").setItems(
						sessionNames.toArray(new String[sessionList.size()]),
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int which) {
								try {
									sessionId = sessionList.get(which).id();
									sessionName = sessionList.get(which).name();
									myClient.joinSession(sessionId, null);
								} catch (CollabrifyException e) {
									System.err.println("error" + e);
								}
							}
						});

				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						builder.show();
					}
				});
			}

			@Override
			public void onSessionCreated(long id) {
				Log.i("session", "Session created, id: " + id);
				sessionId = id;
				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						createSessionButton.setText(sessionName);
						User.initialize();
						if (baseFile)
						{
							User.shadow = to_broadcast.getText().toString();
							to_broadcast.setSelection(0);
						}
						else
							to_broadcast.setText("");
						continuousString = "";
						continuousCount = 0;
					}
				});
			}

			@Override
			public void onError(CollabrifyException e) {
				System.err.println("error" + e);
			}

			@Override
			public void onSessionJoined(long maxOrderId, final long baseFileSize) {
				Log.i("session", "Session Joined"); // implement base file stuff
				if (baseFileSize > 0) {
					// initialize buffer to receive base file

					baseFileReceiveBuffer = new ByteArrayOutputStream(
							(int) baseFileSize);
				}
				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						createSessionButton.setText(sessionName);
						if (baseFileSize == 0) {
							Log.i("session", " WITHOUT base file");
							to_broadcast.setText("");
							User.initialize();
							continuousString = "";
							continuousCount = 0;
						} else {
							Log.i("session", " WITH base file");
							to_broadcast.setText(baseFileReceiveBuffer
									.toString()); // not sure if works, how to receive	
							to_broadcast.setSelection(0);
							User.initialize();
							User.shadow = baseFileReceiveBuffer.toString();
							continuousString = "";
							continuousCount = 0;
						}
					}
				});
			}

			/*
			 * (non-Javadoc)
			 * 
			 * @see edu.umich.imlc.collabrify.client.CollabrifyAdapter#
			 * onBaseFileChunkRequested (long)
			 */
			@Override
			public byte[] onBaseFileChunkRequested(long currentBaseFileSize) {
				// read up to max chunk size at a time
				byte[] temp = new byte[CollabrifyClient.MAX_BASE_FILE_CHUNK_SIZE];
				int read = 0;
				try {
					read = baseFileBuffer.read(temp);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if (read == -1) {
					return null;
				}
				if (read < CollabrifyClient.MAX_BASE_FILE_CHUNK_SIZE) {
					// Trim garbage data
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					bos.write(temp, 0, read);
					temp = bos.toByteArray();
				}
				return temp;
			}

			/*
			 * (non-Javadoc)
			 * 
			 * @see edu.umich.imlc.collabrify.client.CollabrifyAdapter#
			 * onBaseFileChunkReceived (byte[])
			 */
			@Override
			public void onBaseFileChunkReceived(byte[] baseFileChunk) {
				try {
					if (baseFileChunk != null) {
						baseFileReceiveBuffer.write(baseFileChunk);
					} else {
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								// to_broadcast.setText(baseFileReceiveBuffer.toString());
							}
						});
						baseFileReceiveBuffer.close();
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}

			/*
			 * (non-Javadoc)
			 * 
			 * @see edu.umich.imlc.collabrify.client.CollabrifyAdapter#
			 * onBaseFileUploadComplete (long)
			 */
			@Override
			public void onBaseFileUploadComplete(long baseFileSize) {
				runOnUiThread(new Runnable() {

					@Override
					public void run() {
						// to_broadcast.setText(baseFileReceiveBuffer.toString());
					}
				});
				try {
					baseFileBuffer.close();

				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		};

		boolean getLatestEvent = false;

		// Instantiate client object
		try {
			myClient = new CollabrifyClient(this, "user email",
					"user display name", "441fall2013@umich.edu",
					"XY3721425NoScOpE", getLatestEvent, collabrifyListener);
		} catch (CollabrifyException e) {
			e.printStackTrace();
		}

		tags.add("sample");

	}

	/*
	 * generate action for insert/delete
	 */
	void generateInsertDelete() {
		Move retmove;
		if (continuousCount > 0) // add
		{
			User.cursorLoc += continuousCount;

			System.out.println("user manual ADD: " + continuousString
					+ ", after add, cursor @ " + User.cursorLoc);

			EditCom com = new EditCom(User.Operation.ADD, continuousString,
					continuousCount);
			User.undoList.add(com);
			retmove = com.generateMoveMes(0);
			sendretMove(retmove, "add");
		} else // delete
		{
			User.cursorLoc += continuousCount;

			System.out.println("user manual DELETE: " + continuousString
					+ ", after delete, cursor @ " + User.cursorLoc);

			EditCom com = new EditCom(User.Operation.DELETE, continuousString,
					-continuousCount);
			User.undoList.add(com);
			retmove = com.generateMoveMes(0);
			sendretMove(retmove, "del");
		}

		continuousCount = 0;
		continuousString = continuousString.substring(0, 0);

		User.redoList.clear();
	}

	/*
	 * broadcast retMove
	 */
	void sendretMove(Move retMove, String operation) {
		try {
			User.lastsubId = myClient.broadcast(retMove.toByteArray(),
					operation);
			User.needToSynchronize = false;
			Log.i("success", operation + " broadcasting success");
		} catch (CollabrifyException e) {
			Log.i("failed", "broadcasting failed");
			e.printStackTrace();
		}
	}
}
