package com.cwf.speechdialer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 16000;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 100;
    private static final int CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final String filename = "recording.pcm";
    private static final String apiUrl = "http://35.187.148.83:8000/upload";

    private boolean recording;

    private AudioRecord recorder;
    private Thread recordingThread;
    private int bufferSize;
    private RequestQueue queue;

    private boolean permissionToRecordAccepted = false;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case REQUEST_RECORD_AUDIO_PERMISSION:
                permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                break;
        }
        if (!permissionToRecordAccepted) finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recording = false;
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING) * 3;

        queue = Volley.newRequestQueue(this);

        final Button btn = (Button) findViewById(R.id.button);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(recording) {
                    btn.setText(R.string.start);
                    recording = false;
                } else {
                    btn.setText(R.string.dial);
                    recording = true;
                    MainActivity.this.startRecording();
                }
            }
        });

        ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
    }

    private void stopRecording() {
        recorder.stop();
        recorder.release();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((ProgressBar) findViewById(R.id.progressBar)).setVisibility(View.VISIBLE);
            }
        });

        try {
            VolleyMultipartRequest request = new VolleyMultipartRequest(Request.Method.POST, apiUrl, new Response.Listener<NetworkResponse>() {
                @Override
                public void onResponse(NetworkResponse response) {
                    try {
                        String resp = new String(response.data, HttpHeaderParser.parseCharset(response.headers));
                        JSONObject obj = new JSONObject(resp);
                        String status = obj.getString("status");
                        if(status.equals("ok")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ((ProgressBar) findViewById(R.id.progressBar)).setVisibility(View.INVISIBLE);
                                }
                            });
                            String number = obj.getString("result");
                            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
                            MainActivity.this.startActivity(intent);
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ((ProgressBar) findViewById(R.id.progressBar)).setVisibility(View.INVISIBLE);
                                    Toast.makeText(MainActivity.this, "Server error", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    error.printStackTrace();
                }
            }) {
                @Override
                protected Map<String, String> getParams() throws AuthFailureError {
                    return new HashMap<String, String>();
                }

                @Override
                protected Map<String, DataPart> getByteData() throws AuthFailureError {
                    Map<String, DataPart> map = new HashMap<>();
                    try {
                        File file = new File(getFilesDir(), filename);
                        byte bytes[] = new byte[(int) file.length()];
                        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
                        in.readFully(bytes);
                        in.close();
                        map.put("file", new DataPart(filename, bytes, "application/octet-stream"));
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                    return map;
                }
            };

            queue.add(request);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private void startRecording() {
        deleteFile(filename);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNELS, ENCODING, bufferSize);
        if(recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            recording = false;
            ((Button) findViewById(R.id.button)).setText(R.string.start);
            return;
        }

        recorder.startRecording();

        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                writeData();
            }
        });

        recordingThread.start();
    }

    private void writeData() {
        byte data[] = new byte[bufferSize];
        FileOutputStream os;
        try {
            os = openFileOutput(filename, Context.MODE_PRIVATE);
        } catch(Exception e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Failed to start recording", Toast.LENGTH_SHORT).show();
                    recording = false;
                    ((Button) findViewById(R.id.button)).setText(R.string.start);
                }
            });
            return;
        }

        int read = 0;
        while(recording) {
            read = recorder.read(data, 0, bufferSize);
            if(read < 0) {
                Log.d("SD", "recording interrupted");
                break;
            }
            try {
                os.write(data, 0, read);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        try {
            os.flush();
            os.close();
        } catch(Exception e) {
            e.printStackTrace();
        }

        stopRecording();
    }

}
