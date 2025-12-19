// LayoutDebugHelper.kt
package com.derbi.xiaoxia.utils

import android.view.View
import android.view.ViewGroup
import android.util.Log

object LayoutDebugHelper {
    private const val TAG = "LayoutDebug"

    fun printViewHierarchy(view: View, indent: String = "") {
        Log.d(TAG, "$indent${view.javaClass.simpleName} - id: ${view.id}")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                printViewHierarchy(view.getChildAt(i), "$indent  ")
            }
        }
    }

    fun findDuplicateViews(root: View, targetId: Int): List<View> {
        val results = mutableListOf<View>()
        findDuplicateViewsRecursive(root, targetId, results)
        return results
    }

    private fun findDuplicateViewsRecursive(view: View, targetId: Int, results: MutableList<View>) {
        if (view.id == targetId) {
            results.add(view)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findDuplicateViewsRecursive(view.getChildAt(i), targetId, results)
            }
        }
    }
}