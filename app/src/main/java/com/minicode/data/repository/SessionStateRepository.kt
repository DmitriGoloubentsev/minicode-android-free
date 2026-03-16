package com.minicode.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionStateRepository"

@Singleton
class SessionStateRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("minicode_settings", Context.MODE_PRIVATE)

    data class SavedSessionState(
        val sessions: List<SavedSession>,
        val activeIndex: Int,
    )

    data class SavedSession(
        val profileId: String,
        val label: String,
        val sessioSessionName: String?,
        val layout: SavedLayoutState,
        val editorTabs: List<SavedEditorTab>,
        val activeEditorTabIndex: Int,
        val fileTreePath: String,
    )

    data class SavedLayoutState(
        val fileTreeWidthRatio: Float,
        val editorHeightRatio: Float,
        val fileTreeVisible: Boolean,
        val editorVisible: Boolean,
        val activePanel: String,
        val showingEditor: Boolean,
    )

    data class SavedEditorTab(
        val filePath: String,
        val fileName: String,
        val languageId: String,
    )

    fun save(state: SavedSessionState) {
        try {
            val json = toJson(state)
            prefs.edit().putString("saved_sessions", json.toString()).apply()
            Log.d(TAG, "Saved ${state.sessions.size} sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session state", e)
        }
    }

    fun load(): SavedSessionState? {
        val raw = prefs.getString("saved_sessions", null) ?: return null
        return try {
            val state = fromJson(JSONObject(raw))
            if (state.sessions.isEmpty()) null else state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session state", e)
            null
        }
    }

    fun clear() {
        prefs.edit().remove("saved_sessions").apply()
        Log.e(TAG, "Cleared saved sessions", Exception("stack trace"))
    }

    private fun toJson(state: SavedSessionState): JSONObject {
        val sessionsArray = JSONArray()
        for (s in state.sessions) {
            val layoutJson = JSONObject().apply {
                put("fileTreeWidthRatio", s.layout.fileTreeWidthRatio.toDouble())
                put("editorHeightRatio", s.layout.editorHeightRatio.toDouble())
                put("fileTreeVisible", s.layout.fileTreeVisible)
                put("editorVisible", s.layout.editorVisible)
                put("activePanel", s.layout.activePanel)
                put("showingEditor", s.layout.showingEditor)
            }
            val editorTabsArray = JSONArray()
            for (tab in s.editorTabs) {
                editorTabsArray.put(JSONObject().apply {
                    put("filePath", tab.filePath)
                    put("fileName", tab.fileName)
                    put("languageId", tab.languageId)
                })
            }
            sessionsArray.put(JSONObject().apply {
                put("profileId", s.profileId)
                put("label", s.label)
                if (s.sessioSessionName != null) put("sessioSessionName", s.sessioSessionName)
                put("layout", layoutJson)
                put("editorTabs", editorTabsArray)
                put("activeEditorTabIndex", s.activeEditorTabIndex)
                put("fileTreePath", s.fileTreePath)
            })
        }
        return JSONObject().apply {
            put("sessions", sessionsArray)
            put("activeIndex", state.activeIndex)
        }
    }

    private fun fromJson(json: JSONObject): SavedSessionState {
        val sessionsArray = json.getJSONArray("sessions")
        val sessions = mutableListOf<SavedSession>()
        for (i in 0 until sessionsArray.length()) {
            val sj = sessionsArray.getJSONObject(i)
            val lj = sj.getJSONObject("layout")
            val layout = SavedLayoutState(
                fileTreeWidthRatio = lj.getDouble("fileTreeWidthRatio").toFloat(),
                editorHeightRatio = lj.getDouble("editorHeightRatio").toFloat(),
                fileTreeVisible = lj.getBoolean("fileTreeVisible"),
                editorVisible = lj.getBoolean("editorVisible"),
                activePanel = lj.getString("activePanel"),
                showingEditor = lj.getBoolean("showingEditor"),
            )
            val etArray = sj.getJSONArray("editorTabs")
            val editorTabs = mutableListOf<SavedEditorTab>()
            for (j in 0 until etArray.length()) {
                val tj = etArray.getJSONObject(j)
                editorTabs.add(SavedEditorTab(
                    filePath = tj.getString("filePath"),
                    fileName = tj.getString("fileName"),
                    languageId = tj.getString("languageId"),
                ))
            }
            sessions.add(SavedSession(
                profileId = sj.getString("profileId"),
                label = sj.getString("label"),
                sessioSessionName = if (sj.has("sessioSessionName")) sj.getString("sessioSessionName") else null,
                layout = layout,
                editorTabs = editorTabs,
                activeEditorTabIndex = sj.getInt("activeEditorTabIndex"),
                fileTreePath = sj.getString("fileTreePath"),
            ))
        }
        return SavedSessionState(
            sessions = sessions,
            activeIndex = json.getInt("activeIndex"),
        )
    }
}
