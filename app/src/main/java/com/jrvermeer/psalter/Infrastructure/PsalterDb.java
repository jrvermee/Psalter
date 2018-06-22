package com.jrvermeer.psalter.Infrastructure;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Environment;
import android.support.v7.widget.DrawableUtils;
import android.widget.Toast;

import com.android.vending.expansion.zipfile.APKExpansionSupport;
import com.android.vending.expansion.zipfile.ZipResourceFile;
import com.jrvermeer.psalter.R;
import com.jrvermeer.psalter.UI.Adaptors.PsalterSearchAdapter;
import com.jrvermeer.psalter.Core.Contracts.IPsalterRepository;
import com.jrvermeer.psalter.Core.Models.Psalter;
import com.jrvermeer.psalter.Core.Models.SqLiteQuery;
import com.readystatesoftware.sqliteasset.SQLiteAssetHelper;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * Created by Jonathan on 3/27/2017.
 */
// SQLiteAssetHelper: https://github.com/jgilfelt/android-sqlite-asset-helper
public class PsalterDb extends SQLiteAssetHelper implements IPsalterRepository {
    private static final int DATABASE_VERSION = 20;
    private static final String DATABASE_NAME = "psalter.sqlite";
    private static  final String TABLE_NAME = "psalter";
    private SQLiteDatabase db;
    private Context context;

    private static final int EXPANSION_MAIN = 19;
    private static final int EXPANSION_PATCH = 19;

    public PsalterDb(final Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
        setForcedUpgrade(DATABASE_VERSION);
        db = getReadableDatabase();
    }

    public int getCount(){
        try{
            return (int)DatabaseUtils.queryNumEntries(db, TABLE_NAME);
        } catch (Exception ex){;
            return 0;
        }
    }

    public Psalter getIndex(int index){
        Psalter[] results = queryPsalter("_id = " + index, null, "1");
        if(results.length == 0) return null;
        return  results[0];
    }

    public Psalter getPsalter(int number){
        Psalter[] results = queryPsalter("number = " + number, null, "1");
        if(results.length == 0) return null;
        return results[0];
    }
    public Psalter getRandom(){
        //SELECT * FROM table WHERE id IN (SELECT id FROM table ORDER BY RANDOM() LIMIT x)
        Psalter[] results = queryPsalter("_id IN (SELECT _id FROM " + TABLE_NAME + " ORDER BY RANDOM() LIMIT 1)", null , null);
        return results[0];
    }

    public Psalter[] getPsalm(int psalmNumber){
        return queryPsalter("psalm = " + String.valueOf(psalmNumber), null, null);
    }

    public Psalter[] searchPsalter(String searchText){
        Cursor c = null;
        try{
            ArrayList<Psalter> hits = new ArrayList<>();
            SqLiteQuery lyrics = LyricsReplacePunctuation();
            lyrics.addParameter("%" + searchText + "%");

            c = db.rawQuery("select _id, number, psalm, " + lyrics.getQueryText() + " l from psalter where l like ?", lyrics.getParameters() );
            while(c.moveToNext()){
                Psalter p = new Psalter();
                p.setId(c.getInt(0));
                p.setNumber(c.getInt(1));
                p.setPsalm(c.getInt(2));
                p.setLyrics(c.getString(3));
                hits.add(p);
            }
            return hits.toArray(new Psalter[hits.size()]);
        } catch (Exception ex){
            return null;
        }
        finally {
            if(c != null) c.close();
        }
    }

    private Psalter[] queryPsalter(String where, String[] parms, String limit){
        Cursor c = null;
        ArrayList<Psalter> hits = new ArrayList<>();
        try{
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();


            String[] columns = {"_id", "number", "psalm", "title", "lyrics", "numverses", "heading", "AudioFileName", "ScoreFileName", "NumVersesInsideStaff"};
            qb.setTables(TABLE_NAME);
            c = qb.query(db, columns, where, parms, null, null, null, limit);
            while(c.moveToNext()){
                Psalter p = new Psalter();

                p.setId(c.getInt(0));
                p.setNumber(c.getInt(1));
                p.setPsalm(c.getInt(2));
                p.setTitle(c.getString(3));
                p.setLyrics(c.getString(4));
                p.setNumverses(c.getInt(5));
                p.setHeading(c.getString(6));
                p.setAudioFileName(c.getString(7));
                p.setScoreFileName(c.getString(8));
                p.setNumVersesInsideStaff(c.getInt(9));
                hits.add(p);
            }
        } catch (Exception ex){
            Toast.makeText(context, "error", Toast.LENGTH_SHORT).show();
        }
        finally {
            if(c != null) c.close();
        }
        return hits.toArray(new Psalter[hits.size()]);
    }
    private SqLiteQuery LyricsReplacePunctuation(){
        SqLiteQuery query = new SqLiteQuery();
        query.setQueryText("lyrics");
        for(String ignore : context.getResources().getStringArray(R.array.search_ignore_strings)){
            query.setQueryText("replace(" + query.getQueryText() + ", ?, '')");
            query.addParameter(ignore);
        }
        return query;
    }

    public AssetFileDescriptor getAudioDescriptor(Psalter psalter) {
        ZipResourceFile expansionFile = getExpansionFile();
        if(expansionFile == null) return null;

        String fileName = "main." + EXPANSION_MAIN + "." + context.getPackageName() + "/1912/Audio/" + psalter.getAudioFileName();
        AssetFileDescriptor afd = expansionFile.getAssetFileDescriptor(fileName);
        if(afd == null){
            Toast.makeText(context, "Audio not available for this number", Toast.LENGTH_SHORT).show();
            return  null;
        }
        else return afd;
    }

    public Drawable getScore(Psalter psalter){
        ZipResourceFile expansionFile = getExpansionFile();
        if(expansionFile == null) return  null;

        String fileName = "main." + EXPANSION_MAIN + "." + context.getPackageName() + "/1912/Score/" + psalter.getScoreFileName();
        try{
            InputStream stream = expansionFile.getInputStream(fileName);
            return DrawableWrapper.createFromStream(stream, "src");
            //return BitmapFactory.decodeStream(stream);
        }
        catch (Exception ex){
            Toast.makeText(context, "Error reading image", Toast.LENGTH_SHORT).show();
            return  null;
        }
    }

    private ZipResourceFile getExpansionFile(){
        try {
            if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                return APKExpansionSupport.getAPKExpansionZipFile(context, EXPANSION_MAIN, EXPANSION_PATCH);
            }
            else {
                Toast.makeText(context, "External storage must be mounted to play audio and view score", Toast.LENGTH_SHORT).show();
                return  null;
            }
        }
        catch (Exception ex){
            Toast.makeText(context, "Error finding audio file directory", Toast.LENGTH_SHORT).show();
            return  null;
        }
    }
}