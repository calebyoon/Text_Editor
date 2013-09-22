package local.texteditor;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umich.imlc.android.common.Utils;
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
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.*;


public class MainActivity extends Activity 
{
	private final String TAG1 = "adds";
	private final String TAG2 = "dels";
	public final static String EXTRA_MESSAGE = "local.myfirstapp.message";
	private EditText to_broadcast;
	private String continuousString = "";
	private int continuousCount = 0;
	private long startTime;
	Vector cursors = new Vector();
	Vector moves = new Vector();
	
	private static final Level LOGGING_LEVEL = Level.ALL;	

	private CollabrifyClient myClient;
	private Button createSessionButton;
	private Button joinSessionButton;
	private Button leaveSessionButton;
	private CheckBox withBaseFile;
	private CollabrifyListener collabrifyListener;
	private ArrayList<String> tags = new ArrayList<String>();
	private long sessionId;
	private String sessionName;
	private ByteArrayInputStream baseFileBuffer;
	private ByteArrayOutputStream baseFileReceiveBuffer;
	
	
	@Override
	protected void onCreate(Bundle savedInstanceState) 
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		
		
		/*
		 * get the edittext and link user to edittext
		 */
		to_broadcast = (EditText) findViewById(R.id.to_broadcast);
	    User.to_broadcast = to_broadcast;
	    
	    
	    
	    /*
	     * timer thread
	     */
	 	new Thread () {
	 		public void run() 
	 		{
	 			startTime = System.currentTimeMillis();
	 			while(true) 
	 			{
	 				if (System.currentTimeMillis() - startTime >= 600 
	 					&& continuousCount != 0)
	 					generateInsertDelete(); 
	 			}
	 		}
	 	}.start();

	    
	    
		/*
		 * define undo/redo buttons
		 */
	    Button undoButton = (Button) findViewById(R.id.UndoButton);
	    Button redoButton = (Button) findViewById(R.id.RedoButton);
	    undoButton.setOnClickListener(new OnClickListener()
	    {
	    	@Override
	    	public void onClick(View v)
	    	{
	    		if (continuousCount != 0)
	    			generateInsertDelete(); 
	    		
	    		EditCom com = User.Undo(); //now broadcast retMove
	    		if (com != null)
	    		{	
	    			Move retmove = com.generateMoveMes(1);
	    		}   
	    	}
	    });
	    redoButton.setOnClickListener(new OnClickListener()
	    {
	    	@Override
	    	public void onClick(View v)
	    	{
	    		if (continuousCount != 0)
	    			generateInsertDelete(); 
	    		
	    		EditCom com = User.Redo(); //now broadcast retMove
	    		if (com != null)
	    		{	
	    			Move retmove = com.generateMoveMes(2);
	    		}  
	    	}
	    });
	    
	    
	    
	    /*
	     * define basic operation listener
	     */
	    to_broadcast.setSingleLine(false);   
	    to_broadcast.setHorizontallyScrolling(false); 
	    to_broadcast.setLongClickable(false);
	    to_broadcast.setOnClickListener(new View.OnClickListener() 
	    {	
	    	@Override
	    	public void onClick(View v) 
	    	{  
	    		if (continuousCount != 0)
	    			generateInsertDelete(); 
	    		
	    		int cursorNewLoc = to_broadcast.getSelectionEnd();
	    		int offset = cursorNewLoc - User.cursorLoc;
	    		User.CursorChange(User.Id, offset);   
	    		EditCom com = new EditCom(User.Operation.CURSOR, null, offset);
	    		User.undoList.add(com);
	    		Move retMove = com.generateMoveMes(0);
	    		
	    		User.redoList.clear();
	    	}
	    });
	   
	    
	    
		to_broadcast.addTextChangedListener(new TextWatcher() 
		{
			@Override
			public void afterTextChanged(Editable s) 
			{}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) 
			{
				if (User.isTextSetManually)
				{
					if (count > after) //delete
					{
						Log.i(TAG2, "sequence: " + s);
						Log.i(TAG2, "start: " + start);
						Log.i(TAG2, "count: " + count);
						Log.i(TAG2, "after: " + after);
						Log.i(TAG2, "characters deleted: " + s.toString().substring(start, start+count) );			
					
			    		if (continuousCount > 0)
			    			generateInsertDelete(); 
						
						startTime = System.currentTimeMillis();
						continuousCount--;
						continuousString = s.toString().substring(start, start+count) + continuousString;
					}
				}
			}

			
			
			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) 
			{
				if (User.isTextSetManually)
				{
					if (count < before) //this is a delete, deal with adding it to the queue elsewhere
					{}
					else if (count > before) //this is an add
					{
						Log.i(TAG1, "sequence: " + s);
						Log.i(TAG1, "start: " + start);
						Log.i(TAG1, "before: " + before);
						Log.i(TAG1, "count: " + count);
						Log.i(TAG1, "characters added: " + s.toString().substring(start, (start+count)) );
					
			    		if (continuousCount < 0)
			    			generateInsertDelete(); 
						
						startTime = System.currentTimeMillis();
						continuousCount++;
						continuousString += s.toString().substring(start, start+count);
					}
					else //this is a full replace
					{}
				}
				else
				{
					User.isTextSetManually = true;
				}
			}
		});	
		
		
		
		/*
		 * createSession, joinSession, leaveSession, receiveEvent, broadcastEvetn
		 */
	    withBaseFile = (CheckBox) findViewById(R.id.withBaseFileCheckBox);
	    createSessionButton = (Button) findViewById(R.id.CreateButton);
	    joinSessionButton = (Button) findViewById(R.id.JoinButton);
	    leaveSessionButton = (Button) findViewById(R.id.LeaveButton);
	    // enable logging
	    Logger.getLogger("edu.umich.imlc.collabrify.client").setLevel(LOGGING_LEVEL);


	    
	    createSessionButton.setOnClickListener(new OnClickListener()
	    {
	      @Override
	      public void onClick(View v)
	      {
	        try
	        {
	          Random rand = new Random();
	          sessionName = "Test " + rand.nextInt(Integer.MAX_VALUE);

	          if( withBaseFile.isChecked() )
	          {
	            baseFileBuffer = new ByteArrayInputStream(to_broadcast.getText().toString().getBytes());
	            myClient.createSessionWithBase(sessionName, tags, null, 0);
	          }
	          else
	          {
	            myClient.createSession(sessionName, tags, null, 0);
	          }
	          System.out.println("Session name is " + sessionName);
	        }
	        catch( CollabrifyException e )
	        {
	          System.err.println("error " + e);
	        }
	      }
	    });

	    
	    
	    joinSessionButton.setOnClickListener(new OnClickListener()
	    {

	      @Override
	      public void onClick(View v)
	      {
	        try
	        {
	          myClient.requestSessionList(tags);
	        }
	        catch( Exception e )
	        {
		      System.err.println("error " + e);
	        }
	      }
	    });

	    
	    
	    leaveSessionButton.setOnClickListener(new OnClickListener()
	    {
	      @Override
	      public void onClick(View v)
	      {
	        try
	        {
	          if( myClient.inSession() )
	            myClient.leaveSession(false);
	        }
	        catch( CollabrifyException e )
	        {
			  System.err.println("error " + e);
	        }
	      }
	    });

	    
	    
	    collabrifyListener = new CollabrifyAdapter()
	    {
	      @Override
	      public void onDisconnect()
	      {
	        System.out.println("disconnected");
	        runOnUiThread(new Runnable()
	        {

	          @Override
	          public void run()
	          {
	            createSessionButton.setText("Create");
	          }
	        });
	      }

	      @Override
	      public void onReceiveEvent(final long orderId, int subId,
	          String eventType, final byte[] data)
	      {
	        System.out.println("RECEIVED SUB ID:" + subId);
	        runOnUiThread(new Runnable()
	        {
	        	@Override
				public void run() {
					// TODO Auto-generated method stub			
				}
	        });
	      }

	      @Override
	      public void onReceiveSessionList(final List<CollabrifySession> sessionList)
	      {
	        if( sessionList.isEmpty() )
	        {
	          System.out.println("No session available");
	          return;
	        }
	        List<String> sessionNames = new ArrayList<String>();
	        for( CollabrifySession s : sessionList )
	        {
	          sessionNames.add(s.name());
	        }
	        final AlertDialog.Builder builder = new AlertDialog.Builder(
	            MainActivity.this);
	        builder.setTitle("Choose Session").setItems(
	            sessionNames.toArray(new String[sessionList.size()]),
	            new DialogInterface.OnClickListener()
	            {
	              @Override
	              public void onClick(DialogInterface dialog, int which)
	              {
	                try
	                {
	                  sessionId = sessionList.get(which).id();
	                  sessionName = sessionList.get(which).name();
	                  myClient.joinSession(sessionId, null);
	                }
	                catch( CollabrifyException e )
	                {
	                  System.err.println("error" + e);
	                }
	              }
	            });

	        runOnUiThread(new Runnable()
	        {

	          @Override
	          public void run()
	          {
	            builder.show();
	          }
	        });
	      }

	      @Override
	      public void onSessionCreated(long id)
	      {
	        System.out.println("Session created, id: " + id);
	        sessionId = id;
	        runOnUiThread(new Runnable()
	        {

	          @Override
	          public void run()
	          {
	            createSessionButton.setText(sessionName);
	          }
	        });
	      }

	      @Override
	      public void onError(CollabrifyException e)
	      {
	       System.err.println("error" + e);
	      }

	      @Override
	      public void onSessionJoined(long maxOrderId, long baseFileSize)
	      {
	        System.out.println("Session Joined");
	        if( baseFileSize > 0 )
	        {
	          //initialize buffer to receive base file
	          baseFileReceiveBuffer = new ByteArrayOutputStream((int) baseFileSize);
	        }
	        runOnUiThread(new Runnable()
	        {

	          @Override
	          public void run()
	          {
	            createSessionButton.setText(sessionName);
	          }
	        });
	      }

	      /*
	       * (non-Javadoc)
	       * 
	       * @see
	       * edu.umich.imlc.collabrify.client.CollabrifyAdapter#onBaseFileChunkRequested
	       * (long)
	       */
	      @Override
	      public byte[] onBaseFileChunkRequested(long currentBaseFileSize)
	      {
	        // read up to max chunk size at a time
	        byte[] temp = new byte[CollabrifyClient.MAX_BASE_FILE_CHUNK_SIZE];
	        int read = 0;
	        try
	        {
	          read = baseFileBuffer.read(temp);
	        }
	        catch( IOException e )
	        {
	          // TODO Auto-generated catch block
	          e.printStackTrace();
	        }
	        if( read == -1 )
	        {
	          return null;
	        }
	        if( read < CollabrifyClient.MAX_BASE_FILE_CHUNK_SIZE )
	        {
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
	       * @see
	       * edu.umich.imlc.collabrify.client.CollabrifyAdapter#onBaseFileChunkReceived
	       * (byte[])
	       */
	      @Override
	      public void onBaseFileChunkReceived(byte[] baseFileChunk)
	      {
	        try
	        {
	          if( baseFileChunk != null )
	          {
	            baseFileReceiveBuffer.write(baseFileChunk);
	          }
	          else
	          {
	            runOnUiThread(new Runnable()
	            {
	              @Override
	              public void run()
	              {
	                to_broadcast.setText(baseFileReceiveBuffer.toString());
	              }
	            });
	            baseFileReceiveBuffer.close();
	          }
	        }
	        catch( IOException e )
	        {
	          // TODO Auto-generated catch block
	          e.printStackTrace();
	        }
	      }

	      /*
	       * (non-Javadoc)
	       * 
	       * @see
	       * edu.umich.imlc.collabrify.client.CollabrifyAdapter#onBaseFileUploadComplete
	       * (long)
	       */
	      @Override
	      public void onBaseFileUploadComplete(long baseFileSize)
	      {
	        runOnUiThread(new Runnable()
	        {

	          @Override
	          public void run()
	          {
	           // to_broadcast.setText(baseFileReceiveBuffer.toString());
	          }
	        });
	        try
	        {
	          baseFileBuffer.close();
	        }
	        catch( IOException e )
	        {
	          // TODO Auto-generated catch block
	          e.printStackTrace();
	        }
	      }
	    };

	    boolean getLatestEvent = false;

	    // Instantiate client object
	    try
	    {
	      myClient = new CollabrifyClient(this, "user email", "user display name",
	          "441fall2013@umich.edu", "XY3721425NoScOpE", getLatestEvent,
	          collabrifyListener);
	    }
	    catch( CollabrifyException e )
	    {
	      e.printStackTrace();
	    }

	    tags.add("sample");
		
	}
	
	
	
	/*
	 * generate action for insert/delete
	 */
	void generateInsertDelete()
	{
		Move retMove;
		if (continuousCount > 0) // add
		{
			User.cursorLoc += continuousCount;
			
			System.out.println("user manual ADD: " + continuousString + ", after add, cursor @ " + User.cursorLoc);
			
			EditCom com = new EditCom(User.Operation.ADD, continuousString, continuousCount);
			User.undoList.add(com);
			retMove = com.generateMoveMes(0);
		}
		else // delete
		{
			User.cursorLoc += continuousCount;
			
			System.out.println("user manual DELETE: " + continuousString + ", after delete, cursor @ " + User.cursorLoc);
			
			EditCom com = new EditCom(User.Operation.DELETE, continuousString, -continuousCount);
			User.undoList.add(com);
			retMove = com.generateMoveMes(0);
		}
		
		continuousCount = 0;
		continuousString = continuousString.substring(0, 0);
		
		User.redoList.clear();
		
		// now broadcast retMove
	}
	
	
	
	  @Override
	  public boolean onCreateOptionsMenu(Menu menu)
	  {
	    getMenuInflater().inflate(R.menu.main, menu);
	    return true;
	  }
	
	
	
}
