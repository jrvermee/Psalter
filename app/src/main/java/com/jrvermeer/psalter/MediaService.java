package com.jrvermeer.psalter;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.NotificationCompat;
import android.widget.Toast;

import com.jrvermeer.psalter.Models.Psalter;

import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Jonathan on 4/3/2017.
 */

public class MediaService extends Service implements AudioManager.OnAudioFocusChangeListener, MediaPlayer.OnCompletionListener {
    private MediaBinder mBinder = new MediaBinder();
    private IMediaCallbacks mediaCallbacks = null;

    private PsalterDb db;
    private AudioManager audioManager;
    private MediaPlayer mediaPlayer = new MediaPlayer();
    private State mState = null;
    private Random rand = new Random();
    private Lock lock = new ReentrantLock();
    private BroadcastReceiver becomingNoisyReceiver;

    private final int MS_BETWEEN_VERSES = 700;
    private final int NOTIFICATION_ID = 1234;
    private final String ACTION = "action";
    private static int ACTION_STOP = 1;
    private static int ACTION_NEXT = 2;
    private static int ACTION_PLAY = 3;

    @Override
    public void onCreate() {
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        db = new PsalterDb(this);
        becomingNoisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.getAction().equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)){
                    stopMedia(false);
                }
            }
        };
        registerReceiver(becomingNoisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public void onDestroy(){
        unregisterReceiver(becomingNoisyReceiver);
        mediaPlayer.release();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null ){
            int action = intent.getIntExtra(ACTION, -1);
            if(action == ACTION_STOP){
                stopMedia(false);
            }
            else if(action == ACTION_NEXT){
                playRandomNumber();
            }
            else if(action == ACTION_PLAY && mState != null){
                playPsalter(mState);
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    public void setCallbacks(@NonNull final IMediaCallbacks callbacks){
        mediaCallbacks = callbacks;
        if(isPlaying()){
            mediaCallbacks.setCurrentNumber(mState.psalter.getNumber());
            mediaCallbacks.playerStarted();
        }
    }

    public void playPsalterNumber(int number){
        Psalter psalter = db.getPsalter(number);
        playPsalter(new State(psalter, false));
    }
    public void shuffleAllAudio(int firstNumber){
        Psalter psalter = db.getPsalter(firstNumber);
        playPsalter(new State(psalter, true));
        Toast.makeText(this, "Shuffling", Toast.LENGTH_SHORT).show();
    }
    private void playRandomNumber(){
        int nextIndex = rand.nextInt(db.getCount());
        Psalter psalter = db.getPsalter(nextIndex + 1);
        playPsalter(new State(psalter, true));
        if(mediaCallbacks != null){
            mediaCallbacks.setCurrentNumber(psalter.getNumber());
        }
    }
    private boolean playPsalter(@NonNull State state){
        try{
            if(audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mediaPlayer.reset();
                mState = state;
                AssetFileDescriptor afd = getAssetFileDescriptor(mState.psalter);
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                mediaPlayer.setOnCompletionListener(this);
                mediaPlayer.prepare();
                mediaPlayer.start();
                if(mediaCallbacks != null) mediaCallbacks.playerStarted();
                startForeground(NOTIFICATION_ID, getNotification());
                return true;
            }
            else return false;
        }
        catch(Exception ex) {
            return false;
        }
    }
    private boolean playNextVerse(){
        mState.currentVerse++;
        if(mState.currentVerse <= mState.psalter.getNumverses()){
            mState.betweenVerses = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if(mState != null && mState.betweenVerses){ // media could have been stopped between verses
                        mediaPlayer.start();
                        startForeground(NOTIFICATION_ID, getNotification());
                        mState.betweenVerses = false;
                    }
                }
            }, MS_BETWEEN_VERSES);
            return true;
        }
        return false;
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        lock.lock();
        if(mState == null) return;

        if(!playNextVerse()){
            if(mState.isShuffling){
                playRandomNumber();
            }
            else playbackStopped(true);
        }
        lock.unlock();
    }

    private AssetFileDescriptor getAssetFileDescriptor(Psalter psalter) {
        int resID = getResources().getIdentifier(psalter.getAudioFileName(), "raw", getPackageName());
        return getApplicationContext().getResources().openRawResourceFd(resID);
    }

    public void stopMedia(){
        stopMedia(true);
    }
    private void stopMedia(boolean removeNotification){
        lock.lock();
        if(isPlaying()){
            if(mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            playbackStopped(removeNotification);
        }
        lock.unlock();
    }

    public boolean isPlaying(int number){
        return isPlaying() && mState.psalter.getNumber() == number;
    }
    public boolean isPlaying(){
        return mediaPlayer.isPlaying() || (mState != null && mState.betweenVerses);
    }

    private void playbackStopped(boolean removeNotification){
        stopForeground(removeNotification);
        if(removeNotification) mState = null;
        else {
            mState.betweenVerses = false;
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, getNotification());
        }

        audioManager.abandonAudioFocus(this);
        if(mediaCallbacks != null){
            mediaCallbacks.playerFinished();
        }
    }

    @Override
    public void onAudioFocusChange(int i) {
        if(i == AudioManager.AUDIOFOCUS_LOSS || i == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            stopMedia(false);
        }
        else if(i == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            mediaPlayer.setVolume(0.1f, 0.1f);
        }
        else if(i == AudioManager.AUDIOFOCUS_GAIN){
            mediaPlayer.setVolume(1f, 1f);
        }
    }
    private Notification getNotification() {
        Intent openActivity = new Intent(this, MainActivity.class);
        PendingIntent openActivityOnTouch = PendingIntent.getActivity(this, 0, openActivity, PendingIntent.FLAG_UPDATE_CURRENT);

        int numActions = 0;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(MediaService.this);
        builder.setSmallIcon(R.drawable.ic_smallicon)
                //.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setContentTitle(getNotificationTitle(mState.psalter))
                .setContentText(String.format("Verse %d of %d", mState.currentVerse, mState.psalter.getNumverses()))
                .setSubText(mState.psalter.getSubtitleText())
                .setContentIntent(openActivityOnTouch)
                .setShowWhen(false);
        if(isPlaying()){
            Intent stopPlayback = new Intent(this, MediaService.class).putExtra(ACTION, ACTION_STOP);
            PendingIntent stopPlaybackOnTouch = PendingIntent.getService(this, 1, stopPlayback, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_stop_white_36dp, "Stop", stopPlaybackOnTouch)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        }
        else{
            Intent startPlayback = new Intent(this, MediaService.class).putExtra(ACTION, ACTION_PLAY);
            PendingIntent startPlaybackOnTouch = PendingIntent.getService(this, 2, startPlayback, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_play_arrow_white_36dp, "Play", startPlaybackOnTouch);
        }
        numActions++;
        if(mState.isShuffling){
            Intent playNext = new Intent(this, MediaService.class).putExtra(ACTION, ACTION_NEXT);
            PendingIntent playNextOnTouch = PendingIntent.getService(this, 3, playNext, PendingIntent.FLAG_UPDATE_CURRENT);

            builder.addAction(R.drawable.ic_skip_next_white_36dp, "Next", playNextOnTouch);
            numActions++;
        }
        builder.setStyle(new NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(Util.CreateRange(0, numActions - 1)));

        return  builder.build();
    }

    private Handler handler = new Handler();
    private String getNotificationTitle(Psalter psalter){
        return "#" + psalter.getNumber() + " - " + psalter.getHeading();
    }


    public class MediaBinder extends Binder{
        public MediaService getServiceInstance(){
            return MediaService.this;
        }
    }

    private class State {
        public State(Psalter p, boolean isShuffling){
            psalter = p;
            currentVerse = 1;
            betweenVerses = false;
            this.isShuffling = isShuffling;
        }
        private int currentVerse;
        private boolean betweenVerses;
        private Psalter psalter;
        private boolean isShuffling;
    }

    public interface IMediaCallbacks {
        void playerStarted();
        void playerFinished();
        void setCurrentNumber(int number);
    }
}
