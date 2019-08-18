package com.jrvermeer.psalter.UI.Activities;

import com.jrvermeer.psalter.BuildConfig;
import com.jrvermeer.psalter.Core.Contracts.IPagerCallbacks;
import com.jrvermeer.psalter.Core.Models.LogEvent;
import com.jrvermeer.psalter.Core.Models.Psalter;
import com.jrvermeer.psalter.Core.Models.SearchMode;
import com.jrvermeer.psalter.Infrastructure.Helpers;
import com.jrvermeer.psalter.Infrastructure.Log;
import com.jrvermeer.psalter.Infrastructure.SimpleStorage;
import com.jrvermeer.psalter.Infrastructure.TutorialHelper;
import com.jrvermeer.psalter.R;
import com.jrvermeer.psalter.Core.Contracts.IPsalterRepository;
import com.jrvermeer.psalter.Infrastructure.MediaService;
import com.jrvermeer.psalter.Infrastructure.PsalterDb;
import com.jrvermeer.psalter.UI.Adaptors.PsalterPagerAdapter;
import com.jrvermeer.psalter.UI.Adaptors.PsalterSearchAdapter;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnItemClick;
import butterknife.OnLongClick;
import butterknife.OnPageChange;

public class MainActivity extends AppCompatActivity implements
        SearchView.OnQueryTextListener,
        IPagerCallbacks {

    private SimpleStorage storage = new SimpleStorage();
    private SearchMode searchMode = SearchMode.Psalter;
    private IPsalterRepository psalterRepository  = new PsalterDb();
    private MediaControllerCompat mediaController;
    private TutorialHelper tutorials;

    @BindView(R.id.viewpager) ViewPager viewPager;
    @BindView(R.id.fabToggleScore) FloatingActionButton fabToggleScore;
    @BindView(R.id.fab) FloatingActionButton fab;
    @BindView(R.id.lvSearchResults) ListView lvSearchResults;
    @BindView(R.id.toolbar) Toolbar toolbar;
    @BindView(R.id.tableButtons) TableLayout tableButtons;
    @BindView(R.id.searchBtn_Lyrics) Button  searchBtn_Lyrics;
    @BindView(R.id.searchBtn_Psalm) Button searchBtn_Psalm;
    @BindView(R.id.searchBtn_Psalter) Button searchBtn_Psalter;
    MenuItem searchMenuItem;
    SearchView searchView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // must initialize theme before calling setContentView()
        if(storage.isNightMode()) setTheme(R.style.AppTheme_Dark);
        else setTheme(R.style.AppTheme_Light);

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        setSupportActionBar(toolbar);

        Helpers.selectFab(storage.scoreShown(), fabToggleScore, storage.isNightMode());
        //initialize viewpager
        PsalterPagerAdapter pagerAdapter = new PsalterPagerAdapter(this, psalterRepository,
                this, storage.scoreShown(), storage.isNightMode());
        viewPager.setAdapter(pagerAdapter);
        viewPager.setCurrentItem(storage.getPageIndex());

        lvSearchResults.setAdapter(new PsalterSearchAdapter(this, psalterRepository));

        // initialize media service
        Intent intent = new Intent(this, MediaService.class);
        bindService(intent, mConnection, Service.BIND_AUTO_CREATE);

        tutorials = new TutorialHelper(this);
        tutorials.showShuffleTutorial(fab);
        tutorials.showScoreTutorial(fabToggleScore);
    }

    @Override
    protected void onDestroy() {
        // onSaveInstanceState is not called when using back button to close application
        if(isFinishing()){
            mediaController.getTransportControls().stop();
            unbindService(mConnection);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed(){
        if(lvSearchResults.isShown()) {  collapseSearchView(); }
        else super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_options, menu);

        menu.findItem(R.id.action_nightmode).setChecked(storage.isNightMode());

        searchMenuItem = menu.findItem(R.id.action_search);
        searchView = (SearchView)searchMenuItem.getActionView();
        searchView.setOnQueryTextListener(this);

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                showSearchButtons();
                return true;
            }
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                hideSearchResultsScreen();
                hideSearchButtons();
                return true;
            }
        });
        searchPsalter();
        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if(searchMode == SearchMode.Lyrics){
            performStringSearch(query);
        }
        else {
            try {
                int number = Integer.parseInt(query);
                if(searchMode == SearchMode.Psalter){
                    if(1 <= number && number <= 434){
                        collapseSearchView();
                        goToPsalter(number);
                        Log.searchEvent(searchMode, query, null);
                    }
                    else{
                        Toast.makeText(this, "Pick a number between 1 and 434", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                else if (searchMode == SearchMode.Psalm){
                    if(1 <= number && number <= 150){
                        performPsalmSearch(number);
                    }
                    else{
                        Toast.makeText(this, "Pick a number between 1 and 150", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                else return false;

            } catch (NumberFormatException ex){
                return false;
            }
        }
        searchView.clearFocus();

        return true;
    }
    @Override
    public boolean onQueryTextChange(String newText) {
        if(searchMode == SearchMode.Lyrics && newText.length() > 1){
            performStringSearch(newText);
            return true;
        }
        return false;
    }
    @OnClick(R.id.searchBtn_Psalm)
    public void searchPsalm(){
        searchView.setInputType(InputType.TYPE_CLASS_NUMBER);
        searchView.setQueryHint("Enter Psalm (1 - 150)");
        searchMode = SearchMode.Psalm;
        if(Build.VERSION.SDK_INT < 23){
            searchBtn_Psalter.setTextAppearance(this, R.style.Button);
            searchBtn_Psalm.setTextAppearance(this, R.style.Button_Selected);
            searchBtn_Lyrics.setTextAppearance(this, R.style.Button);
        }
        else{
            searchBtn_Psalter.setTextAppearance(R.style.Button);
            searchBtn_Psalm.setTextAppearance(R.style.Button_Selected);
            searchBtn_Lyrics.setTextAppearance(R.style.Button);
        }
    }
    @OnClick(R.id.searchBtn_Lyrics)
    public void searchLyrics() {
        searchView.setInputType(InputType.TYPE_CLASS_TEXT);
        searchView.setQueryHint("Enter search query");
        searchMode = SearchMode.Lyrics;
        if(Build.VERSION.SDK_INT < 23){
            searchBtn_Psalter.setTextAppearance(this, R.style.Button);
            searchBtn_Psalm.setTextAppearance(this, R.style.Button);
            searchBtn_Lyrics.setTextAppearance(this, R.style.Button_Selected);
        }
        else {
            searchBtn_Psalter.setTextAppearance(R.style.Button);
            searchBtn_Psalm.setTextAppearance(R.style.Button);
            searchBtn_Lyrics.setTextAppearance(R.style.Button_Selected);
        }
    }
    @OnClick(R.id.searchBtn_Psalter)
    public void searchPsalter(){
        searchView.setInputType(InputType.TYPE_CLASS_NUMBER);
        searchView.setQueryHint("Enter Psalter number (1 - 434)");
        searchMode = SearchMode.Psalter;
        if(Build.VERSION.SDK_INT < 23){
            searchBtn_Psalter.setTextAppearance(this, R.style.Button_Selected);
            searchBtn_Psalm.setTextAppearance(this, R.style.Button);
            searchBtn_Lyrics.setTextAppearance(this, R.style.Button);
        }
        else{
            searchBtn_Psalter.setTextAppearance(R.style.Button_Selected);
            searchBtn_Psalm.setTextAppearance(R.style.Button);
            searchBtn_Lyrics.setTextAppearance(R.style.Button);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.action_nightmode){
            boolean nightMode = storage.toggleNightMode();
            item.setChecked(nightMode);
            Log.changeTheme(nightMode);

            if(Build.VERSION.SDK_INT == 23){ // framework bug in api 23 calling recreate inside onOptionsItemSelected.
                finish();
                startActivity(getIntent());
            }
            else recreate();
        }
        else if(id == R.id.action_random){
            if(mediaController.getShuffleMode() == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    && mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING){
                mediaController.getTransportControls().skipToNext();
            }
            else {
                Psalter psalter = psalterRepository.getRandom();
                viewPager.setCurrentItem(psalter.getId(), true);
            }
            Log.event(LogEvent.GoToRandom);
        }
        else if(id == R.id.action_shuffle) {
            tutorials.showShuffleReminderTutorial(fab);
            shuffle();
        }
        else if(id == R.id.action_rate){
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.jrvermeer.psalter")));
        }
        else if(id == R.id.action_sendfeedback){
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                    "mailto","jrvermeer.dev@gmail.com", null));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Psalter App");
            String body = "\n\n\n";
            body += "---------------------------\n";
            body += "App version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n";
            body += "Android version: " + Build.VERSION.RELEASE;
            emailIntent.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(emailIntent, "Send email..."));
        }
        else return false;

        return true;
    }

    private void performStringSearch(String query){
        showSearchResultsScreen();
        ((PsalterSearchAdapter)lvSearchResults.getAdapter()).queryPsalter(query);
        lvSearchResults.setSelectionAfterHeaderView();
    }
    private void performPsalmSearch(int psalm){
        showSearchResultsScreen();
        ((PsalterSearchAdapter)lvSearchResults.getAdapter()).getAllFromPsalm(psalm);
        lvSearchResults.setSelectionAfterHeaderView();
    }

    private void goToPsalter(int psalterNumber){
        Psalter psalter = psalterRepository.getPsalter(psalterNumber);
        viewPager.setCurrentItem(psalter.getId(), true); //viewpager goes by index
    }
    private void showSearchResultsScreen(){
        lvSearchResults.setVisibility(View.VISIBLE);
        viewPager.setVisibility(View.GONE);
        fab.hide();
    }
    private void hideSearchResultsScreen(){
        lvSearchResults.setVisibility(View.GONE);
        viewPager.setVisibility(View.VISIBLE);
        fab.show();
    }
    private void collapseSearchView(){
        if(searchMenuItem != null && searchMenuItem.isActionViewExpanded()) searchMenuItem.collapseActionView();
    }
    private void showSearchButtons(){
        tableButtons.setVisibility(View.VISIBLE);
        fabToggleScore.hide();
        fab.hide();
    }
    private void hideSearchButtons(){
        tableButtons.setVisibility(View.GONE);
        fabToggleScore.show();
        fab.show();
    }

    @OnClick(R.id.fabToggleScore)
    public void toggleScore(){
        PsalterPagerAdapter adapter = (PsalterPagerAdapter)viewPager.getAdapter();
        if(storage.toggleScore()) {
            adapter.showScore();
            Helpers.selectFab(true, fabToggleScore, storage.isNightMode());
        }
        else {
            adapter.hideScore();
            Helpers.selectFab(false, fabToggleScore, storage.isNightMode());
        }

        int currentPage = viewPager.getCurrentItem();
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPage);

        Log.changeScore(storage.scoreShown());
    }


    @OnClick(R.id.fab)
    public void fabClick() {
        if(mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING){
            mediaController.getTransportControls().stop();
        }
        else {
            Psalter psalter = getSelectedPsalter();
            mediaController.getTransportControls().setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
            mediaController.getTransportControls().playFromMediaId(String.valueOf(psalter.getId()), null);
            Log.playbackStarted(psalter.getTitle(), false);
        }
    }
    @OnLongClick(R.id.fab)
    public boolean shuffle(){
        Psalter psalter = getSelectedPsalter();
        mediaController.getTransportControls().setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL);
        mediaController.getTransportControls().playFromMediaId(String.valueOf(psalter.getId()), null);
        Log.playbackStarted(psalter.getTitle(), true);

        tutorials.showShuffleRandomTutorial(toolbar.findViewById(R.id.action_random));
        return true;
    }

    @OnItemClick(R.id.lvSearchResults)
        public void onItemClick(View view) {
        try {
            TextView tvNumber = ((PsalterSearchAdapter.ViewHolder)view.getTag()).tvNumber;
            int num = Integer.parseInt(tvNumber.getText().toString());

            //log event before collapsing searchview, so we can log the query text
            Log.searchEvent(searchMode, searchView.getQuery().toString(), String.valueOf(num));

            collapseSearchView();
            goToPsalter(num);
        } catch (Exception ex) {
            Toast.makeText(MainActivity.this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void pageCreated(View page, int position) {
        if(position == viewPager.getCurrentItem()){
            tutorials.showGoToPsalmTutorial(page.findViewById(R.id.tvPagerPsalm));
        }
    }

    @OnPageChange(value = R.id.viewpager)
    public void onPageSelected(int index) {
        storage.setPageIndex(index);
        if(mediaController != null && mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING //if media is playing
                && !mediaController.getMetadata().getDescription().getMediaId().equals(String.valueOf(index))){ // and it's not the media for current page
            mediaController.getTransportControls().stop();
        }
    }

    private Psalter getSelectedPsalter(){
        return psalterRepository.getIndex(viewPager.getCurrentItem());
    }
    ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            try{
                mediaController = new MediaControllerCompat(MainActivity.this, ((MediaService.MediaBinder) iBinder).getSessionToken());
                mediaController.registerCallback(callback);
                callback.onPlaybackStateChanged(mediaController.getPlaybackState());
            }
            catch (RemoteException ex){

            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    MediaControllerCompat.Callback callback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            if(state.getState() == PlaybackStateCompat.STATE_PLAYING){
                fab.setImageResource(R.drawable.ic_stop_white_24dp);
            }
            else fab.setImageResource(R.drawable.ic_play_arrow_white_24dp);

            if(fab.isShown()) {  // stupid ass bug, setting images fails after toggling night mode. https://stackoverflow.com/a/52158081
                fab.hide();
                fab.show();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if(mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING){
                int id = Integer.parseInt(metadata.getDescription().getMediaId());
                viewPager.setCurrentItem(id);
            }
        }
    };
}
