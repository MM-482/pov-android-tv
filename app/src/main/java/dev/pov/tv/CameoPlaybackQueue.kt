package dev.pov.tv

import dev.pov.tv.data.PlaybackIdentity

/** A queue contains saved identities; a catalog entry always returns to explicit source selection. */
object CameoPlaybackQueue {
    private var entries: List<CameoLibraryEntry> = emptyList()
    private var index = -1
    private var open: ((CameoLibraryEntry) -> Unit)? = null
    var name: String = ""
        private set
    fun start(name: String, entries: List<CameoLibraryEntry>, index: Int = 0, open: (CameoLibraryEntry) -> Unit) {
        require(index in entries.indices) { "This playlist is empty." }
        this.name = name; this.entries = entries.toList(); this.index = index; this.open = open
        open(entries[index])
    }
    fun current(): CameoLibraryEntry? = entries.getOrNull(index)
    fun rebind(open:(CameoLibraryEntry)->Unit) {if(entries.isNotEmpty())this.open=open}
    fun acceptsPlay(key:String,identity:PlaybackIdentity?):Boolean {
        val entry=current()?:return false
        if(entry.id==key)return true
        if(entry.kind!="catalog")return false
        val saved=entry.identity?:return false
        return identity!=null&&saved.catalogNamespace==identity.catalogNamespace&&saved.imdbId==identity.imdbId&&saved.type==identity.type&&
            (saved.episode==0 || saved.season==identity.season&&saved.episode==identity.episode)
    }
    fun hasPrevious() = index > 0 && open != null
    fun hasNext() = index + 1 in entries.indices && open != null
    fun previous(): Boolean = move(index - 1)
    fun next(): Boolean = move(index + 1)
    fun onPlaybackEnded(): Boolean = next()
    private fun move(next: Int): Boolean {
        val action = open ?: return false
        val entry = entries.getOrNull(next) ?: return false
        index = next; action(entry); return true
    }
    fun clear() { entries = emptyList(); index = -1; open = null; name = "" }
}
