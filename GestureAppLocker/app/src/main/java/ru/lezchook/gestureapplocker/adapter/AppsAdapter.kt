package ru.lezchook.gestureapplocker.adapter

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.lezchook.gestureapplocker.R
import ru.lezchook.gestureapplocker.database.AppDB
import ru.lezchook.gestureapplocker.model.AppModel

class AppAdapter(private var context_: Context, private var appModels: List<AppModel>, private var database: AppDB?):
    RecyclerView.Adapter<AppAdapter.adapterDesign>() {

    class  adapterDesign(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var appName: TextView = itemView.findViewById(R.id.appname)
        var appIcon: ImageView = itemView.findViewById(R.id.appicon)
        var appStatus: ImageView = itemView.findViewById(R.id.appstatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): adapterDesign {
        val view: View = LayoutInflater.from(context_).inflate(R.layout.adapter_panel, parent, false)
        return adapterDesign(view)
    }

    override fun getItemCount(): Int {
        return appModels.size
    }

    override fun onBindViewHolder(holder: adapterDesign, position: Int) {
        val app: AppModel = appModels[position]
        holder.appName.text = app.appName
        holder.appIcon.setImageDrawable(byteArrayToDrawable(app.appIcon))
        if (app.status == 0) {
            holder.appStatus.setImageResource(R.drawable.unlock)
        } else {
            holder.appStatus.setImageResource(R.drawable.lock)
        }
        holder.appStatus.setOnClickListener {
            if (app.status == 0) {
                appModels[position].status = 1
                database?.getAppDao()?.updateAppModel(app.id, 1)
                holder.appStatus.setImageResource(R.drawable.lock)
            } else {
                appModels[position].status = 0
                database?.getAppDao()?.updateAppModel(app.id, 0)
                holder.appStatus.setImageResource(R.drawable.unlock)
            }
        }
    }

    private fun byteArrayToDrawable(byteArray: ByteArray): Drawable {
        val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        return BitmapDrawable(Resources.getSystem(), bitmap)
    }
}