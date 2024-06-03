package ru.lezchook.gestureapplocker.fragment

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import ru.lezchook.gestureapplocker.adapter.AppAdapter
import ru.lezchook.gestureapplocker.model.AppModel
import ru.lezchook.gestureapplocker.database.AppDB
import ru.lezchook.gestureapplocker.databinding.FragmentAppsBinding
import java.io.ByteArrayOutputStream
import java.lang.IndexOutOfBoundsException


class AppListFragment : Fragment() {

    private lateinit var fragmentAppsBinding: FragmentAppsBinding
    private var appModelList = ArrayList<AppModel>()
    private lateinit var adapter: AppAdapter
    private lateinit var progress: ProgressDialog
    private var database: AppDB? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        fragmentAppsBinding = FragmentAppsBinding.inflate(inflater, container, false)
        initializeDatabase()
        adapter = context?.let { AppAdapter(it, appModelList, database) }!!
        fragmentAppsBinding.recycleView.layoutManager = LinearLayoutManager(context)
        fragmentAppsBinding.recycleView.adapter = adapter
        progress = ProgressDialog(context)
        progress.setOnShowListener {
            getInstalledApps()
        }
        return fragmentAppsBinding.root
    }

    override fun onResume() {
        super.onResume()
        progress.show()
    }

    private fun initializeDatabase() {
        database = context?.let {
            Room.databaseBuilder(it, AppDB::class.java, "AppLockerDB")
                .allowMainThreadQueries()
                .build()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun getInstalledApps() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].blockedFlag == 1) {
                    Toast.makeText(context, "Для доступа к списку, осуществите разблокировку", Toast.LENGTH_LONG).show()
                    progress.dismiss()
                    return
                }
            } catch (e: IndexOutOfBoundsException) { }
        }
        val currentList = database?.getAppDao()?.getAllApps()
        if (currentList != null && currentList.isEmpty()) {
            val packageInfo: MutableList<PackageInfo>? =
                activity?.packageManager?.getInstalledPackages(0)
            if (packageInfo != null) {
                for (i in 0 until packageInfo.size) {
                    val p: PackageInfo = packageInfo[i]
                    if (p.packageName == "com.android.settings") {
                        addAppToDatabase(p, i, 1)
                    }
                    if (p.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                        continue
                    }
                    addAppToDatabase(p, i, 0)
                }
            }
        }
        if (database != null) {
            val dbList = database!!.getAppDao().getAllApps() as ArrayList<AppModel>
            for (i in 0 until dbList.size) {
                appModelList.add(dbList[i])
            }
        }
        adapter.notifyDataSetChanged()
        progress.dismiss()
    }

    private fun addAppToDatabase(packageInfo: PackageInfo, index: Int, status: Int) {
        val b = activity?.packageManager
        if (b != null) {
            val name = packageInfo.applicationInfo.loadLabel(b).toString()
            val icon = packageInfo.applicationInfo.loadIcon(b)
            val packname: String = packageInfo.packageName
            val appModel = AppModel(
                id = index,
                appName = name,
                appIcon = drawableToByteArray(icon),
                packageName = packname,
                status = status,
            )
            database?.getAppDao()?.insertAppModel(appModel)
        }
    }

    private fun drawableToByteArray(drawable: Drawable): ByteArray {
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }
}