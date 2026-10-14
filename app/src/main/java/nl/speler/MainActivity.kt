package nl.speler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF090812)
private val Panel = Color(0xFF171426)
private val Panel2 = Color(0xFF211A35)
private val Purple = Color(0xFF9B5CFF)
private val Pink = Color(0xFFEF5DCE)
private val Muted = Color(0xFFAAA4BD)

data class Song(val title: String, val uri: String)
data class Playlist(val name: String, val songs: List<Song>)
data class CustomCard(val title: String, val description: String)
data class CustomTab(val name: String, val cards: List<CustomCard>)

class MainActivity : ComponentActivity() {
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Pink, background = Bg, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple)
                }
            }
        }
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Pink, background = Bg, surface = Panel)) {
                        controller?.let { PlayerApp(this, it) }
                    }
                }
            } catch (_: Exception) {
                setContent { MaterialTheme { Text("De speler kon niet starten. Sluit de app en probeer opnieuw.") } }
            }
        }, mainExecutor)
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

private fun titleFor(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        else uri.lastPathSegment ?: "Audio"
    } ?: (uri.lastPathSegment ?: "Audio")
} catch (_: Exception) { uri.lastPathSegment ?: "Audio" }

private fun timeText(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PlayerApp(context: Context, player: Player) {
    val prefs = remember { context.getSharedPreferences("speler", Context.MODE_PRIVATE) }
    fun loadLists(): List<Playlist> = try {
        val a = JSONArray(prefs.getString("playlists", "[]"))
        (0 until a.length()).map { i ->
            val p = a.getJSONObject(i); val s = p.getJSONArray("songs")
            Playlist(p.getString("name"), (0 until s.length()).map { j ->
                val v = s.getJSONObject(j); Song(v.getString("title"), v.getString("uri"))
            })
        }
    } catch (_: Exception) { emptyList() }

    val playlists = remember { mutableStateListOf<Playlist>().apply { addAll(loadLists()) } }
    fun loadCustomTabs(): List<CustomTab> = try {
        val a = JSONArray(prefs.getString("customTabs", "[]"))
        (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            val cardsJson = t.optJSONArray("cards") ?: JSONArray()
            CustomTab(t.getString("name"), (0 until cardsJson.length()).map { j ->
                val c = cardsJson.getJSONObject(j)
                CustomCard(c.optString("title", "Nieuw item"), c.optString("description", ""))
            })
        }
    } catch (_: Exception) { emptyList() }
    val customTabs = remember { mutableStateListOf<CustomTab>().apply { addAll(loadCustomTabs()) } }
    var selected by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("Kies een nummer") }
    var currentUri by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newTabName by remember { mutableStateOf("") }
    var newCardTitle by remember { mutableStateOf("") }
    var newCardDescription by remember { mutableStateOf("") }
    var sleepTimerMinutes by remember { mutableIntStateOf(prefs.getInt("sleepTimerMinutes", 0)) }
    var sleepTimerRunning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Welkom terug") }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    var playerRevision by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var accentIndex by remember { mutableIntStateOf(prefs.getInt("accent", 0).coerceIn(0, 3)) }
    var compactRows by remember { mutableStateOf(prefs.getBoolean("compactRows", false)) }
    var showMiniPlayer by remember { mutableStateOf(prefs.getBoolean("miniPlayer", true)) }
    val accentOptions = listOf(Color(0xFF9B5CFF), Color(0xFF4D8DFF), Color(0xFF31C99A), Color(0xFFFF9A45))
    val accentAltOptions = listOf(Color(0xFFEF5DCE), Color(0xFF55D8F2), Color(0xFFB4F36B), Color(0xFFFF5F7A))
    val uiAccent = accentOptions[accentIndex]
    val uiAccentAlt = accentAltOptions[accentIndex]

    fun save() {
        val a = JSONArray()
        playlists.forEach { p ->
            val songs = JSONArray()
            p.songs.forEach { songs.put(JSONObject().put("title", it.title).put("uri", it.uri)) }
            a.put(JSONObject().put("name", p.name).put("songs", songs))
        }
        prefs.edit().putString("playlists", a.toString()).apply()
    }
    fun saveCustomTabs() {
        val a = JSONArray()
        customTabs.forEach { t ->
            val cards = JSONArray()
            t.cards.forEach { c -> cards.put(JSONObject().put("title", c.title).put("description", c.description)) }
            a.put(JSONObject().put("name", t.name).put("cards", cards))
        }
        prefs.edit().putString("customTabs", a.toString()).apply()
    }
    fun add(songs: List<Song>) {
        if (selected !in playlists.indices) { message = "Maak eerst een playlist."; return }
        playlists[selected] = playlists[selected].copy(songs = playlists[selected].songs + songs)
        save(); message = "${songs.size} nummer(s) toegevoegd aan ${playlists[selected].name}"
    }

    fun moveSong(from: Int, to: Int) {
        if (selected !in playlists.indices) return
        val songs = playlists[selected].songs.toMutableList()
        if (from !in songs.indices || to !in songs.indices) return
        val moved = songs.removeAt(from)
        songs.add(to, moved)
        playlists[selected] = playlists[selected].copy(songs = songs)
        save()
        message = "Volgorde aangepast"
    }
    fun play(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(songs.indices)
        player.setMediaItems(songs.map { song ->
            MediaItem.Builder().setUri(song.uri).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(song.title).build()
            ).build()
        }, safeIndex, 0L)
        player.prepare()
        player.play()
        currentTitle = songs[safeIndex].title
        currentUri = songs[safeIndex].uri
        playing = true
        position = 0f
        duration = 0L
        tab = 1
        playerRevision++
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        add(uris.map { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Song(titleFor(context, uri), uri.toString())
        })
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151025), Bg, Color(0xFF08070E)))).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(uiAccent, uiAccentAlt))), contentAlignment = Alignment.Center) {
                Text("♫", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("S P E L E R", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text("YOUR SOUND. YOUR WORLD.", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
            }
            Box(Modifier.clip(CircleShape).background(Panel2).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("● ONLINE", color = Color(0xFF9BE7C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(16.dp)).background(Color(0xAA171426)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("Bibliotheek", "Speler", "Downloads", "Instellingen") + customTabs.map { it.name }).forEachIndexed { i, label ->
                Box(
                    Modifier.widthIn(min = 76.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (tab == i) Brush.linearGradient(listOf(uiAccent, Color(0xFF7343D6))) else Brush.linearGradient(listOf(Panel, Panel)))
                        .clickable { tab = i }.padding(horizontal = 10.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (tab == i) Color.White else Muted, fontSize = 10.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
            }
        }

        when (tab) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("JOUW MUZIEK", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        Text("Bibliotheek", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${playlists.sumOf { it.songs.size }} tracks", color = uiAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, modifier = Modifier.weight(1f), label = { Text("Nieuwe playlist") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            playlists.add(Playlist(newName.trim(), emptyList())); selected = playlists.lastIndex
                            newName = ""; save(); message = "Playlist aangemaakt"
                        }
                    }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 15.dp), shape = RoundedCornerShape(14.dp)) { Text("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = selected in playlists.indices, shape = RoundedCornerShape(12.dp)) { Text("＋ Audio toevoegen", fontSize = 12.sp) }
                    OutlinedButton(onClick = { tab = 3 }, shape = RoundedCornerShape(12.dp)) { Text("↓ Downloads", fontSize = 12.sp) }
                }
                if (selected in playlists.indices) {
                    val playlist = playlists[selected]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selected = -1 }) { Text("← Playlists") }
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("${playlist.songs.size} nummers", color = Muted, fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(12.dp)) { Text("＋ Nummer toevoegen", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                playlists.removeAt(selected)
                                selected = -1
                                save()
                                message = "Playlist verwijderd"
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Verwijder") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play(playlist.songs, 0) }, enabled = playlist.songs.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Text("▶ Alles afspelen", fontSize = 11.sp) }
                        OutlinedButton(onClick = { play(playlist.songs.shuffled(), 0) }, enabled = playlist.songs.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Text("⤨ Willekeurig", fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Zoek een nummer") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    val visibleSongs = playlist.songs.mapIndexed { originalIndex, song -> originalIndex to song }
                        .filter { it.second.title.contains(searchQuery, ignoreCase = true) }
                    if (playlist.songs.isEmpty()) {
                        EmptyCard("Playlist is leeg", "Voeg je eigen audiobestanden toe om ze hier te zien.")
                    } else {
                        Text("NUMMERS IN DEZE PLAYLIST", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(visibleSongs) { _, entry ->
                                val (i, song) = entry
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(if (compactRows) 6.dp else 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("${i + 1}", color = uiAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                                    Column(
                                        Modifier.weight(1f).clickable { play(playlist.songs, i) },
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(song.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Tik om af te spelen", color = Muted, fontSize = 10.sp)
                                    }
                                    TextButton(
                                        onClick = { moveSong(i, i - 1) },
                                        enabled = i > 0,
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text("↑", color = if (i > 0) Color.White else Muted, fontSize = 20.sp) }
                                    TextButton(
                                        onClick = { moveSong(i, i + 1) },
                                        enabled = i < playlist.songs.lastIndex,
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text("↓", color = if (i < playlist.songs.lastIndex) Color.White else Muted, fontSize = 20.sp) }
                                    TextButton(
                                        onClick = {
                                            playlists[selected] = playlist.copy(songs = playlist.songs.filterIndexed { j, _ -> j != i })
                                            save()
                                            message = "Nummer verwijderd"
                                        },
                                        contentPadding = PaddingValues(horizontal = 5.dp)
                                    ) { Text("×", color = uiAccentAlt, fontSize = 20.sp) }
                                }
                            }
                        }
                    }
                } else {
                    Text("JOUW PLAYLISTS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    if (playlists.isEmpty()) {
                        EmptyCard("Nog geen playlists", "Maak hierboven een playlist om te beginnen.")
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(playlists) { i, playlist ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                        .background(Brush.horizontalGradient(listOf(Panel, Panel)))
                                        .clickable { selected = i }.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(uiAccent.copy(alpha = .8f), uiAccentAlt.copy(alpha = .6f)))), contentAlignment = Alignment.Center) {
                                        Text("♫", color = Color.White, fontSize = 27.sp)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("${playlist.songs.size} nummers", color = Muted, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { selected = i; play(playlist.songs, 0) }, enabled = playlist.songs.isNotEmpty()) { Text("▶", color = uiAccent, fontSize = 20.sp) }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                Text("NU AAN HET SPELEN", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Jouw soundtrack.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF43236C), Color(0xFF211A3D), Color(0xFF151321)))),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(174.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(uiAccent, Color(0xFF4D2D91), uiAccentAlt))), contentAlignment = Alignment.Center) {
                            Text("♫", color = Color.White, fontSize = 100.sp, fontWeight = FontWeight.Light)
                        }
                        Text("SPOTLIGHT", color = Color.White.copy(alpha = .6f), fontSize = 10.sp, letterSpacing = 4.sp)
                    }
                }
                Text(currentTitle, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (currentUri.startsWith("http")) "Online audio" else "Lokale muziekbibliotheek", color = Muted, fontSize = 12.sp)
                LaunchedEffect(player, playerRevision) {
                    while (true) {
                        val d = player.duration
                        if (d > 0 && d != androidx.media3.common.C.TIME_UNSET) {
                            duration = d
                            position = (player.currentPosition.toFloat() / d.toFloat()).coerceIn(0f, 1f)
                            currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle
                            playing = player.isPlaying
                        }
                        kotlinx.coroutines.delay(400)
                    }
                }
                Slider(value = position.coerceIn(0f, 1f), onValueChange = { position = it; if (duration > 0) player.seekTo((duration * it).toLong()) }, colors = SliderDefaults.colors(thumbColor = uiAccentAlt, activeTrackColor = uiAccent, inactiveTrackColor = Panel2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeText(player.currentPosition), color = Muted, fontSize = 10.sp)
                    Text(timeText(if (player.duration > 0) player.duration else duration), color = Muted, fontSize = 10.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle }) { Text("⤨", color = if (shuffle) uiAccentAlt else Muted, fontSize = 22.sp) }
                    TextButton(onClick = { player.seekToPreviousMediaItem(); currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle }) { Text("◀◀", color = Color.White, fontSize = 19.sp) }
                    Box(Modifier.size(66.dp).clip(CircleShape).background(Brush.linearGradient(listOf(uiAccent, uiAccentAlt))).clickable {
                        if (player.isPlaying) { player.pause(); playing = false } else { player.play(); playing = true }
                        currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle
                    }, contentAlignment = Alignment.Center) { Text(if (playing) "Ⅱ" else "▶", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { player.seekToNextMediaItem(); currentTitle = player.mediaMetadata.title?.toString() ?: currentTitle }) { Text("▶▶", color = Color.White, fontSize = 19.sp) }
                    TextButton(onClick = {
                        player.pause()
                        player.stop()
                        playing = false
                        position = 0f
                        duration = 0L
                        currentTitle = "Kies een nummer"
                        currentUri = ""
                    }) { Text("■", color = uiAccentAlt, fontSize = 18.sp) }
                    TextButton(onClick = { repeat = !repeat; player.repeatMode = if (repeat) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF }) { Text("⟳", color = if (repeat) uiAccentAlt else Muted, fontSize = 22.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Text("Snelheid", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        TextButton(onClick = { speed = value; player.setPlaybackSpeed(value) }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                            Text("${value}x", color = if (speed == value) uiAccentAlt else Muted, fontSize = 11.sp, fontWeight = if (speed == value) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            2 -> {
                Text("JOUW COLLECTIE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Downloads", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                EmptyCard("Geen linkjes meer", "Hou het simpel: voeg muziek toe vanaf je telefoon en beheer alles in je playlists. Bestanden die al in Downloads staan kun je hieronder kiezen.")
                Button(
                    onClick = { tab = 0; picker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("＋ Muziek kiezen van telefoon") }
                Text("Tip: bestanden die je downloadt vind je in de app Mijn bestanden > Downloads.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
            }
            3 -> {
                Text("MAAK HET VAN JOU", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("UI-instellingen", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                EmptyCard("Jouw stijl", "Pas de accentkleur en de weergave van je muziek aan. Je keuzes worden op deze telefoon bewaard.")
                Text("KLEURTHEMA", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Paars", "Blauw", "Groen", "Oranje").forEachIndexed { index, label ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (accentIndex == index) Panel2 else Panel)
                                .clickable {
                                    accentIndex = index
                                    prefs.edit().putInt("accent", index).apply()
                                }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Brush.linearGradient(listOf(accentOptions[index], accentAltOptions[index]))))
                            Text(label, color = Color.White, modifier = Modifier.weight(1f), fontWeight = if (accentIndex == index) FontWeight.Bold else FontWeight.Normal)
                            if (accentIndex == index) Text("✓", color = accentOptions[index], fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Panel2)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compacte playlist", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Meer nummers tegelijk in beeld", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = compactRows, onCheckedChange = {
                        compactRows = it
                        prefs.edit().putBoolean("compactRows", it).apply()
                    })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mini-speler onderaan", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Bediening in andere tabbladen", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = showMiniPlayer, onCheckedChange = {
                        showMiniPlayer = it
                        prefs.edit().putBoolean("miniPlayer", it).apply()
                    })
                }
                HorizontalDivider(color = Panel2)
                Text("SLAAPTIMER", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Stop muziek automatisch", color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0, 15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = sleepTimerMinutes == minutes,
                            onClick = {
                                sleepTimerMinutes = minutes
                                sleepTimerRunning = false
                                prefs.edit().putInt("sleepTimerMinutes", minutes).apply()
                            },
                            label = { Text(if (minutes == 0) "Uit" else "$minutes min") }
                        )
                    }
                }
                Button(
                    onClick = {
                        if (sleepTimerMinutes > 0) {
                            sleepTimerRunning = true
                            message = "Muziek stopt over $sleepTimerMinutes minuten"
                        } else message = "Kies eerst een tijd"
                    },
                    enabled = sleepTimerMinutes > 0,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (sleepTimerRunning) "Timer opnieuw starten" else "Start slaaptimer") }
                if (sleepTimerRunning) {
                    TextButton(onClick = { sleepTimerRunning = false; message = "Slaaptimer geannuleerd" }) { Text("Timer annuleren") }
                }
                LaunchedEffect(sleepTimerRunning, sleepTimerMinutes) {
                    if (sleepTimerRunning && sleepTimerMinutes > 0) {
                        kotlinx.coroutines.delay(sleepTimerMinutes * 60_000L)
                        player.pause()
                        player.stop()
                        playing = false
                        sleepTimerRunning = false
                        currentTitle = "Kies een nummer"
                        currentUri = ""
                        message = "Slaaptimer: muziek gestopt"
                    }
                }
                HorizontalDivider(color = Panel2)
                Text("EIGEN TABBLADEN", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Maak eigen pagina's met notities en kaartjes.", color = Color.White, fontSize = 12.sp)
                OutlinedTextField(
                    value = newTabName,
                    onValueChange = { newTabName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Naam nieuw tabblad") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        val name = newTabName.trim()
                        val names = listOf("Bibliotheek", "Speler", "Downloads", "Instellingen") + customTabs.map { it.name }
                        if (name.isNotBlank() && names.none { it.equals(name, true) }) {
                            customTabs.add(CustomTab(name, emptyList()))
                            saveCustomTabs()
                            newTabName = ""
                            tab = 4 + customTabs.lastIndex
                            message = "Eigen tabblad aangemaakt"
                        } else message = "Kies een unieke naam voor het tabblad"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("＋ Tabblad toevoegen") }
                if (customTabs.isNotEmpty()) {
                    Text("${customTabs.size} eigen tabblad(en) • tik bovenaan om ze te openen", color = Muted, fontSize = 11.sp)
                }
                OutlinedButton(onClick = {
                    accentIndex = 0
                    compactRows = false
                    showMiniPlayer = true
                    sleepTimerMinutes = 0
                    sleepTimerRunning = false
                    prefs.edit().putInt("accent", 0).putBoolean("compactRows", false).putBoolean("miniPlayer", true).putInt("sleepTimerMinutes", 0).apply()
                    message = "Standaardinstellingen hersteld"
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Herstel standaardinstellingen") }
                Spacer(Modifier.weight(1f))
            }
            else -> {
                val customIndex = tab - 4
                if (customIndex in customTabs.indices) {
                    val customTab = customTabs[customIndex]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("JOUW EIGEN RUIMTE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                            Text(customTab.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = {
                            customTabs.removeAt(customIndex)
                            saveCustomTabs()
                            tab = 3
                            message = "Eigen tabblad verwijderd"
                        }) { Text("Verwijder tab", color = uiAccentAlt, fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = newCardTitle,
                        onValueChange = { newCardTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Naam van je item") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = newCardDescription,
                        onValueChange = { newCardDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Beschrijving of notitie") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            if (newCardTitle.isNotBlank()) {
                                customTabs[customIndex] = customTab.copy(cards = customTab.cards + CustomCard(newCardTitle.trim(), newCardDescription.trim()))
                                saveCustomTabs()
                                newCardTitle = ""
                                newCardDescription = ""
                                message = "Item opgeslagen"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("＋ Item maken") }
                    if (customTab.cards.isEmpty()) {
                        EmptyCard("Begin met iets maken", "Maak hier notities, ideeën, lijstjes of projectkaartjes.")
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            itemsIndexed(customTab.cards) { cardIndex, card ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(card.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        if (card.description.isNotBlank()) Text(card.description, color = Muted, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = {
                                        customTabs[customIndex] = customTab.copy(cards = customTab.cards.filterIndexed { j, _ -> j != cardIndex })
                                        saveCustomTabs()
                                        message = "Item verwijderd"
                                    }) { Text("×", color = uiAccentAlt, fontSize = 20.sp) }
                                }
                            }
                        }
                    }
                } else {
                    Text("Tabblad niet gevonden", color = Color.White, fontSize = 20.sp)
                    Button(onClick = { tab = 3 }) { Text("Naar instellingen") }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tab != 1 && showMiniPlayer) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF2A1C43), Panel))).clickable { tab = 1 }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(uiAccent, uiAccentAlt))), contentAlignment = Alignment.Center) { Text("♫", color = Color.White, fontSize = 24.sp) }
                Column(Modifier.weight(1f)) {
                    Text(currentTitle, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Tik om speler te openen", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { if (player.isPlaying) { player.pause(); playing = false } else { player.play(); playing = true } }) { Text(if (playing) "Ⅱ" else "▶", color = uiAccentAlt, fontSize = 21.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyCard(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            Text("♫", color = Purple, fontSize = 30.sp)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(description, color = Muted, fontSize = 12.sp)
    }
}
