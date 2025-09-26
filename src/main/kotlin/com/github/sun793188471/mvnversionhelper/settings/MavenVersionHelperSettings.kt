package com.github.sun793188471.mvnversionhelper.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "MavenVersionHelperSettings",
    storages = [Storage("mavenVersionHelperSettings.xml")]
)
class MavenVersionHelperSettings : PersistentStateComponent<MavenVersionHelperSettings.State> {

    data class State(
        var excludedPaths: MutableList<String> = mutableListOf("/dalgen"),
        var groupIdPrefixes: MutableList<String> = mutableListOf("com.ly")
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getExcludedPaths(): List<String> = myState.excludedPaths.toList()

    fun setExcludedPaths(paths: List<String>) {
        myState.excludedPaths = paths.toMutableList()
    }

    fun getGroupIdPrefixes(): List<String> = myState.groupIdPrefixes.toList()

    fun setGroupIdPrefixes(prefixes: List<String>) {
        myState.groupIdPrefixes = prefixes.toMutableList()
    }

    companion object {
        fun getInstance(project: Project): MavenVersionHelperSettings {
            return project.service<MavenVersionHelperSettings>()
        }
    }
}