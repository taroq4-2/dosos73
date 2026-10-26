package com.os73.dosos73

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable internal fun ScreenHeader(onSearch:()->Unit,onMore:()->Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Brand(true);Spacer(Modifier.weight(1f));IconAction(Glyph.SEARCH,stringResource(R.string.search),onSearch);IconAction(Glyph.MORE,stringResource(R.string.more),onMore)
        }
    }
}
@Composable internal fun SavedFileRow(item:SavedFile,onAction:(SavedFile,String)->Unit,history:Boolean=false) {
    var menu by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    var bitmap by remember(item.uri,item.date) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri,item.date) { bitmap=LibraryRepository.thumbnail(context,item) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Neon.Surface).clickable{onAction(item,"open")}.padding(11.dp).testTag("file_row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(65.dp,75.dp).clip(RoundedCornerShape(10.dp)).background(Neon.Raised),contentAlignment=Alignment.Center) {
                bitmap?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
                    ?:Symbol(when(item.kind){Kind.AUDIO->Glyph.AUDIO;Kind.VIDEO->Glyph.VIDEO;else->Glyph.FOLDER},modifier=Modifier.size(30.dp),color=if(item.kind==Kind.AUDIO)Neon.Purple else Neon.Cyan)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall.copy(textDirection=TextDirection.ContentOrLtr))
                Text(bytesLabel(item.bytes,locale)+"  •  "+DateFormat.getDateInstance(DateFormat.SHORT,locale).format(Date(item.date)),style=MaterialTheme.typography.labelSmall,color=Neon.Secondary)
                if(item.extension.isNotEmpty())Text(item.extension,Modifier.clip(RoundedCornerShape(5.dp)).background(if(item.kind==Kind.AUDIO)Neon.Purple.copy(alpha=0.65f)else Neon.Blue.copy(alpha=0.5f)).padding(horizontal=7.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall)
            }
            Box { IconAction(Glyph.MORE,stringResource(R.string.file_actions),{menu=true})
                DropdownMenu(menu,{menu=false}) {
                    listOf("open" to R.string.open,"share" to R.string.share,"info" to R.string.details).forEach{(key,label)->DropdownMenuItem({Text(stringResource(label))},{menu=false;onAction(item,key)})}
                    if(history)DropdownMenuItem({Text(stringResource(R.string.remove_history))},{menu=false;onAction(item,"hide")})
                    else {
                        DropdownMenuItem({Text(stringResource(R.string.rename))},{menu=false;onAction(item,"rename")})
                        DropdownMenuItem({Text(stringResource(R.string.delete_file),color=MaterialTheme.colorScheme.error)},{menu=false;onAction(item,"delete")})
                    }
                }
            }
        }
    }
}
@Composable internal fun FileLibraryScreen(files:List<SavedFile>,query:String,onAction:(SavedFile,String)->Unit,onRefresh:()->Unit) {
    var category by rememberSaveable{mutableIntStateOf(0)}
    var sort by rememberSaveable{mutableIntStateOf(0)}
    var ascending by rememberSaveable{mutableStateOf(false)}
    val filtered=remember(files,query,category,sort,ascending) {
        val rows=files.filter{(category==0||(category==1&&it.kind==Kind.VIDEO)||(category==2&&it.kind==Kind.AUDIO))&&it.name.contains(query,true)}
        val comparator=when(sort){1->compareBy<SavedFile>{it.name.lowercase()};2->compareBy{it.bytes};else->compareBy{it.date}}
        rows.sortedWith(if(ascending)comparator else comparator.reversed())
    }
    Column(Modifier.fillMaxSize().padding(horizontal=17.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(R.string.all_files,R.string.video,R.string.audio).forEachIndexed {index,label->FilterChip(category==index,{category=index},label={Text(stringResource(label))},leadingIcon={Symbol(when(index){1->Glyph.VIDEO;2->Glyph.AUDIO;else->Glyph.FOLDER},modifier=Modifier.size(18.dp))})}
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ChoiceMenu(stringResource(R.string.sort_by),stringResource(when(sort){1->R.string.filename;2->R.string.file_size;else->R.string.recent})){close->listOf(R.string.recent,R.string.filename,R.string.file_size).forEachIndexed{i,label->DropdownMenuItem({Text(stringResource(label))},{sort=i;close()})}} }
            IconAction(Glyph.SORT,stringResource(if(ascending)R.string.ascending else R.string.descending),{ascending=!ascending})
            IconAction(Glyph.CLOCK,stringResource(R.string.refresh),onRefresh)
        }
        if(filtered.isEmpty())EmptyState(Glyph.FOLDER,R.string.no_files,if(query.isBlank())R.string.files_empty_body else R.string.no_search_results)
        else LazyColumn(Modifier.fillMaxSize().testTag("files_list"),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=16.dp,top=8.dp)) {
            items(filtered,key={it.uri}){SavedFileRow(it,onAction)}
        }
    }
}
@Composable internal fun DownloadsScreen(state:TransferState,queue:List<DownloadTask>,files:List<SavedFile>,query:String,
    onCancel:()->Unit,onPause:()->Unit,onRetry:()->Unit,onOpen:()->Unit,onDetails:()->Unit,onRemove:(DownloadTask)->Unit,onRunQueue:()->Unit,onAction:(SavedFile,String)->Unit) {
    var filter by rememberSaveable {mutableIntStateOf(0)}
    val locale=LocalConfiguration.current.locales[0]
    val completed=files.filter{it.name.contains(query,true)}
    val waiting=queue.filter{it.analysis.title.contains(query,true)}
    Column(Modifier.fillMaxSize().padding(horizontal=17.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf(R.string.active_downloads to if(state.active)1 else 0,R.string.completed_downloads to files.size,R.string.waiting_downloads to queue.size).forEachIndexed { i,pair ->
                    FilterChip(filter==i,{filter=i},label={Text(stringResource(pair.first)+" "+NumberFormat.getIntegerInstance(locale).format(pair.second),style=MaterialTheme.typography.labelSmall)})
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("downloads_list"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=16.dp)) {
            if(filter==0) {
                if(state.phase!=Phase.IDLE&&state.title.contains(query,true))item{TransferCard(state,onCancel,onOpen,onPause,onRetry,onDetails)}
                if(state.phase==Phase.IDLE)item{EmptyState(Glyph.DOWNLOAD,R.string.no_active_downloads,R.string.downloads_empty_body)}
                if(waiting.isNotEmpty())item{Text(stringResource(R.string.waiting_downloads),style=MaterialTheme.typography.titleMedium)}
            }
            if(filter==1) {
                if(completed.isEmpty())item{EmptyState(Glyph.CHECK,R.string.no_completed_downloads,R.string.files_empty_body)}
                items(completed,key={it.uri}){SavedFileRow(it,onAction,true)}
            }
            if(filter==2&&waiting.isEmpty())item{EmptyState(Glyph.CLOCK,R.string.queue_empty,R.string.queue_empty_body)}
            if(filter!=1) {
                if(waiting.isNotEmpty()&&!state.active)item{GradientButton(stringResource(R.string.start_queue),onRunQueue,Modifier.fillMaxWidth(),icon=Glyph.PLAY)}
                items(waiting,key={it.id}) { task -> Panel {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Symbol(Glyph.CLOCK,color=Neon.Muted)
                        Column(Modifier.weight(1f)){Text(task.analysis.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(stringResource(R.string.waiting_to_start),color=Neon.Muted,style=MaterialTheme.typography.bodySmall)}
                        IconAction(Glyph.CLOSE,stringResource(R.string.remove_queue),{onRemove(task)})
                    }
                } }
            }
        }
    }
}

