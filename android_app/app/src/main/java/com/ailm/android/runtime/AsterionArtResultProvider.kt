package com.ailm.android.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID

/** Signed, read/write capability for importing actual generated PNGs into Core. */
class AsterionArtResultProvider : ContentProvider() {
    companion object {
        const val AUTHORITY="com.ailm.android.asterionart.results"
        private val IDS=Regex("[a-f0-9-]{36}")
    }
    private fun directory(name:String)=File(requireNotNull(context).filesDir,
        "asterion-art-inbox/"+name).also { it.mkdirs() }
    private fun identify(uri:Uri):Pair<String,String> {
        require(uri.authority==AUTHORITY && uri.pathSegments.size==2)
        val (type,id)=uri.pathSegments
        require(type=="upload" || type=="asset")
        require(id.matches(IDS))
        return type to id
    }
    override fun onCreate()=true
    override fun getType(uri:Uri):String?=if(uri.pathSegments.firstOrNull()=="asset")
        "image/png" else "application/octet-stream"

    override fun insert(uri:Uri,values:ContentValues?):Uri? {
        require(uri.authority==AUTHORITY && uri.pathSegments==listOf("upload"))
        requireNotNull(values)
        val job=values.getAsString("job_id").orEmpty()
        val digest=values.getAsString("sha256").orEmpty()
        val mode=values.getAsString("render_mode").orEmpty()
        require(job.matches(Regex("[A-Za-z0-9_-]{8,90}")))
        require(digest.matches(Regex("[a-fA-F0-9]{64}")))
        require(mode in setOf("ANIME","COSPLAY","REALISTIC","ANIMATION"))
        val id=UUID.randomUUID().toString()
        File(directory("pending"),id+".json").writeText(JSONObject().apply {
            put("id",id)
            put("job_id",job)
            put("sha256",digest.lowercase())
            put("render_mode",mode)
            put("created",System.currentTimeMillis())
        }.toString())
        return Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("upload").appendPath(id).build()
    }

    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        val (type,id)=identify(uri)
        val file=if(type=="asset")File(directory("assets"),id+".png")
            else File(directory("pending"),id+".part")
        val flags=if(type=="upload") {
            if(mode!="w"&&mode!="wt")throw FileNotFoundException("Upload only supports writing")
            if(!File(directory("pending"),id+".json").isFile)
                throw FileNotFoundException("Unknown upload")
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        } else {
            if(mode!="r"||!file.isFile)throw FileNotFoundException("Unknown result")
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file,flags)
    }
    override fun query(uri:Uri,projection:Array<out String>?,
        selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor? {
        val (type,id)=identify(uri)
        if(type!="asset")throw IllegalArgumentException("Asset queries only")
        val file=File(directory("assets"),id+".png")
        if(!file.isFile)return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name,file.length()))
        }
    }

    override fun update(uri:Uri,values:ContentValues?,
        selection:String?,selectionArgs:Array<out String>?):Int {
        val (type,id)=identify(uri)
        require(type=="upload"&&values?.getAsInteger("commit")==1)
        val pending=File(directory("pending"),id+".part")
        val specFile=File(directory("pending"),id+".json")
        require(pending.isFile && specFile.isFile)
        require(pending.length() in 128L..(100L*1024*1024))
        val meta=JSONObject(specFile.readText())
        val md=MessageDigest.getInstance("SHA-256")
        pending.inputStream().buffered().use { input ->
            val buf=ByteArray(512*1024)
            while(true) {
                val n=input.read(buf)
                if(n<0)break
                if(n>0)md.update(buf,0,n)
            }
        }
        val sha=md.digest().joinToString("") { "%02x".format(it) }
        require(meta.getString("sha256")==sha) { "Artwork SHA-256 mismatch" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        pending.inputStream().use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Not a valid PNG image" }
        val dst=File(directory("assets"),id+".png")
        require(pending.renameTo(dst)) { "Failed to finalize artwork" }
        val asset=Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("asset").appendPath(id).build()
        val now=System.currentTimeMillis()
        meta.put("asset_uri",asset.toString())
        meta.put("width",bounds.outWidth)
        meta.put("height",bounds.outHeight)
        meta.put("status","COMPLETED")
        File(directory("assets"),id+".json").writeText(meta.toString(2))
        specFile.delete()
        LocalDatabase(requireNotNull(context)).use { helper ->
            val db=helper.writableDatabase
            db.beginTransaction()
            try {
                val cursor=db.rawQuery("SELECT COALESCE(MAX(imported_order),0)+1 FROM images",null)
                val order=cursor.use { if(it.moveToFirst())it.getLong(0) else 1L }
                val row=ContentValues().apply {
                    put("uri",asset.toString())
                    put("filename",dst.name)
                    put("folder_uri","content://"+AUTHORITY+"/art-gallery")
                    put("parent_uri","content://"+AUTHORITY+"/art-gallery")
                    put("size_bytes",dst.length())
                    put("width",bounds.outWidth)
                    put("height",bounds.outHeight)
                    put("created_at_ms",now)
                    put("modified_at_ms",now)
                    put("extension","png")
                    put("mime_type","image/png")
                    put("resolution_text",bounds.outWidth.toString()+"x"+bounds.outHeight)
                    put("aspect_ratio",bounds.outWidth.toDouble()/bounds.outHeight)
                    put("folder_name","Asterion Art")
                    put("relative_path","Asterion Art/")
                    put("imported_order",order)
                    put("favorite",0)
                    put("rating",0)
                    put("tags_text","asterion_art generated")
                    put("taxonomy_text","")
                    put("metadata_text",meta.toString())
                    put("active",1)
                    put("last_modified_ms",now)
                    put("scanned_at_ms",now)
                }
                require(db.insertWithOnConflict(
                    "images",null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1L)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        requireNotNull(context).contentResolver.notifyChange(asset,null)
        return 1
    }
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int =
        throw UnsupportedOperationException("Results are managed by Core")
}
