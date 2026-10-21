package com.kaori.androdex.core

/** Small policy gate shared by file access code and local unit tests. */
object ReadAccessPolicy {
    fun requireEnabledRoot(enabled: Boolean) {
        if (!enabled) throw SecurityException("此授权目录已由用户暂停")
    }

    fun requireCurrentPermission(granted: Boolean, description: String) {
        if (!granted) throw SecurityException("系统已撤销或未授予 $description")
    }

    fun requireExistingFile(exists: Boolean) {
        if (!exists) throw java.io.FileNotFoundException("文件或目录不存在")
    }
}
