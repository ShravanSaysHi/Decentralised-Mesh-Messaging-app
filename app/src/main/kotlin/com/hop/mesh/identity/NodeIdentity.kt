package com.hop.mesh.identity

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Phase 2: Persistent unique Node ID (UUID) for this device.
 * Stored in SharedPreferences; generated once on first access.
 */
class NodeIdentity(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** This node's unique ID. Generated and persisted on first access. */
    val nodeId: String
        get() {
            var id = prefs.getString(KEY_NODE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_NODE_ID, id).apply()
            }
            return id
        }

    companion object {
        private const val PREFS_NAME = "hop_node_identity"
        private const val KEY_NODE_ID = "node_id"
    }
}
