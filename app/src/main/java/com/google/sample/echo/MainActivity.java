/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.sample.echo;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import static android.content.ContentValues.TAG;

public class MainActivity extends Activity
        implements ActivityCompat.OnRequestPermissionsResultCallback {
    private static final int AUDIO_ECHO_REQUEST = 0;

    private ToggleButton onOffToggle;
    private TextView tv_status;

    private String  nativeSampleRate;
    private String  nativeSampleBufSize;

    private int echoDelayProgress;

    private float echoDecayProgress;

    private boolean supportRecording;
    private Boolean isPlaying = false;

    private boolean headphonesConnected;


    private AudioOutputIntentReceiver outputReceiver;
    
    // TODO: Possibly lower volume when a phonecall / notification comes in.
    // TODO: Boost amplification
    // TODO: Add more settings
    // TODO: Save Previous Settings

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        onOffToggle = (ToggleButton)findViewById(R.id.on_off_toggle);
        tv_status = (TextView) findViewById(R.id.toggle_label);

        queryNativeAudioParameters();

        // Set the audio output listener
        outputReceiver = new AudioOutputIntentReceiver();


        // initialize native audio system
        updateNativeAudioUI();

        if (supportRecording) {
            createSLEngine(
                    Integer.parseInt(nativeSampleRate),
                    Integer.parseInt(nativeSampleBufSize),
                    echoDelayProgress,
                    echoDecayProgress);
        }
    }

    private void setSeekBarPromptPosition(SeekBar seekBar, TextView label) {
        float thumbX = (float)seekBar.getProgress()/ seekBar.getMax() *
                              seekBar.getWidth() + seekBar.getX();
        label.setX(thumbX - label.getWidth()/2.0f);
    }

    @Override
    protected void onResume() {
        // Register the headphone jack receiver
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        registerReceiver(outputReceiver, intentFilter);
        super.onResume();
    }

    @Override
    protected void onPause() {
        // Unregister the headphone jack receiver
        unregisterReceiver(outputReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (supportRecording) {
            if (isPlaying) {
                stopPlay();
            }
            deleteSLEngine();
            isPlaying = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
            Dialog settingsDialog = new Dialog(this);
            LayoutInflater inflater = (LayoutInflater)this.getSystemService(LAYOUT_INFLATER_SERVICE);
            View layout = inflater.inflate(R.layout.settings_dialog, (ViewGroup)findViewById(R.id.dialog_rootview));
            settingsDialog.setContentView(layout);

            final SeekBar delaySeekBar = (SeekBar)layout.findViewById(R.id.delaySeekBar);
            final TextView curDelayTV = (TextView) layout.findViewById(R.id.curDelay);

            echoDelayProgress = delaySeekBar.getProgress() * 1000 / delaySeekBar.getMax();

            delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float curVal = (float)progress / delaySeekBar.getMax();
                    curDelayTV.setText(String.format("%s", curVal));
                    setSeekBarPromptPosition(delaySeekBar, curDelayTV);
                    if (!fromUser) return;

                    echoDelayProgress = progress * 1000 / delaySeekBar.getMax();
                    configureEcho(echoDelayProgress, echoDecayProgress);
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            delaySeekBar.post(new Runnable() {
                @Override
                public void run() {
                    setSeekBarPromptPosition(delaySeekBar, curDelayTV);
                }
            });

            final SeekBar decaySeekBar = (SeekBar)layout.findViewById(R.id.decaySeekBar);
            final TextView curDecayTV = (TextView)layout.findViewById(R.id.curDecay);
            echoDecayProgress = (float)decaySeekBar.getProgress() / decaySeekBar.getMax();


            decaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float curVal = (float)progress / seekBar.getMax();
                    curDecayTV.setText(String.format("%s", curVal));
                    setSeekBarPromptPosition(decaySeekBar, curDecayTV);
                    if (!fromUser)
                        return;

                    echoDecayProgress = curVal;
                    configureEcho(echoDelayProgress, echoDecayProgress);
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            decaySeekBar.post(new Runnable() {
                @Override
                public void run() {
                    setSeekBarPromptPosition(decaySeekBar, curDecayTV);
                }
            });


            settingsDialog.show();

            return true;
    }

    private void startEcho() {
        if(!supportRecording){
            return;
        }
        if (!isPlaying) {
            if(!createSLBufferQueueAudioPlayer()) {
//                Toast.makeText(this, getString(R.string.player_error_msg) , Toast.LENGTH_SHORT).show();
                return;
            }
            if(!createAudioRecorder()) {
                deleteSLBufferQueueAudioPlayer();
//                Toast.makeText(this, getString(R.string.recorder_error_msg) , Toast.LENGTH_SHORT).show();

                return;
            }
            startPlay();   // startPlay() triggers startRecording()
//            Toast.makeText(this, getString(R.string.echoing_status_msg) , Toast.LENGTH_SHORT).show();

        } else {
            stopPlay();  // stopPlay() triggers stopRecording()
            updateNativeAudioUI();
            deleteAudioRecorder();
            deleteSLBufferQueueAudioPlayer();
        }
        isPlaying = !isPlaying;
        onOffToggle.setText(getString(isPlaying ?
                R.string.On: R.string.Off));
    }

    public void onEchoClick(View view) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                                               PackageManager.PERMISSION_GRANTED) {
//            statusView.setText(getString(R.string.request_permission_status_msg));
            Toast.makeText(this, getString(R.string.request_permission_status_msg) , Toast.LENGTH_SHORT).show();

            ActivityCompat.requestPermissions(
                    this,
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    AUDIO_ECHO_REQUEST);
            return;
        }
        startEcho();
    }

    // Used to see if lowlatency audio is available for this device

    private void queryNativeAudioParameters() {
        supportRecording = true;
        AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if(myAudioMgr == null) {
            supportRecording = false;
            return;
        }
        nativeSampleRate  =  myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        nativeSampleBufSize =myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);

        // hardcoded channel to mono: both sides -- C++ and Java sides
        int recBufSize = AudioRecord.getMinBufferSize(
                Integer.parseInt(nativeSampleRate),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (recBufSize == AudioRecord.ERROR ||
                recBufSize == AudioRecord.ERROR_BAD_VALUE) {
            supportRecording = false;
        }

    }
    private void updateNativeAudioUI() {
        if (!supportRecording) {
            Toast.makeText(this, R.string.mic_error_msg , Toast.LENGTH_SHORT).show();
            onOffToggle.setEnabled(false);
            return;
        }

        if(!headphonesConnected){
            tv_status.setText("Please Plug in \n Head Phones");
            onOffToggle.setEnabled(false);
            onOffToggle.setVisibility(View.INVISIBLE);
           // stopPlay();
            return;
        }else{
            tv_status.setText("Toggle Hearing Aid");
            onOffToggle.setEnabled(true);
            onOffToggle.setVisibility(View.VISIBLE);
            return;
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        /*
         * if any permission failed, the sample could not play
         */
        if (AUDIO_ECHO_REQUEST != requestCode) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 1  ||
            grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            /*
             * When user denied permission, throw a Toast to prompt that RECORD_AUDIO
             * is necessary; also display the status on UI
             * Then application goes back to the original state: it behaves as if the button
             * was not clicked. The assumption is that user will re-click the "start" button
             * (to retry), or shutdown the app in normal way.
             */
            Toast.makeText(getApplicationContext(),
                    getString(R.string.permission_prompt_msg),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        /*
         * When permissions are granted, we prompt the user the status. User would
         * re-try the "start" button to perform the normal operation. This saves us the extra
         * logic in code for async processing of the button listener.
         */
        Toast.makeText(this, getString(R.string.request_permission_status_msg) , Toast.LENGTH_SHORT).show();

        // The callback runs on app's thread, so we are safe to resume the action
        startEcho();
    }


    // Listens to headphones being plugged in / unplugged
    private class AudioOutputIntentReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", -1);
                switch (state) {
                    case 0:
                        Log.d(TAG, "Headset is unplugged");
                        headphonesConnected = false;
                        updateNativeAudioUI();

                        break;
                    case 1:
                        Log.d(TAG, "Headset is plugged");
                        headphonesConnected = true;
                        updateNativeAudioUI();

                        break;
                    default:
                        Log.d(TAG, "I have no idea what the headset state is");
                        headphonesConnected = false;
                        updateNativeAudioUI();
                }
            }
        }
    }



    /*
     * Loading our lib
     */
    static {
        System.loadLibrary("echo");
    }

    /*
     * jni function declarations
     */
    static native void createSLEngine(int rate, int framesPerBuf,
                                      long delayInMs, float decay);
    static native void deleteSLEngine();
    static native boolean configureEcho(int delayInMs, float decay);
    static native boolean createSLBufferQueueAudioPlayer();
    static native void deleteSLBufferQueueAudioPlayer();

    static native boolean createAudioRecorder();
    static native void deleteAudioRecorder();
    static native void startPlay();
    static native void stopPlay();
}
