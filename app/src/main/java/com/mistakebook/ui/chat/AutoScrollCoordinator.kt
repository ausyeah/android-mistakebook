package com.mistakebook.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 聊天页的自动跟随滚动。
 *
 * ## 为什么单独抽出来
 *
 * 「消息来了要不要自动滚到底」是聊天页最容易做错的一处：做错了不崩、不报错，
 * 只是**新消息看不见**，而新消息看不见正是聊天页最不能接受的故障。
 *
 * ## 两条铁律
 *
 * 1. **列表正在滚动时不自动滚**。用户正在翻上面的内容，界面却自己跳到底，
 *    是最招人烦的一种。
 * 2. **用户已经离底就别自作主张**。要滚必须是他自己点「回到底部」。
 */
class AutoScrollCoordinator {

    /**
     * 是否贴着底部。
     *
     * 为 false 时新内容**不**自动跟随（铁律 2），并显示「回到底部」按钮。
     */
    var following by mutableStateOf(true)
        internal set

    /** 是否显示「回到底部」悬浮按钮。 */
    var showJumpButton by mutableStateOf(false)
        internal set

    /** 是否允许此刻自动跟随。 */
    fun canFollow(state: LazyListState): Boolean = following && !state.isScrollInProgress

    /** 用户点了「回到底部」。用 `scrollToItem`（瞬时）而不是 `animateScrollToItem`：这是对点击的直接响应，
     * 加动画反而让用户觉得「我点了但没动」。 */
    suspend fun jumpToBottom(state: LazyListState) {
        following = true
        showJumpButton = false
        val last = state.layoutInfo.totalItemsCount - 1
        if (last >= 0) state.scrollToItem(last)
    }
}

/**
 * 挂上自动跟随。
 *
 * @param contentKey 内容版本号（消息条数，或流式时的正文长度）。变了就尝试跟随。
 */
@Composable
fun rememberAutoScrollEffect(
    state: LazyListState,
    contentKey: Any
): AutoScrollCoordinator {
    val coordinator = remember { AutoScrollCoordinator() }

    // 持续观察是否贴底。distinctUntilChanged 是必要的：
    // layoutInfo 每次重组都是新对象，不去重这个 flow 会不停发射、白烧重组。
    LaunchedEffect(state) {
        snapshotFlow { state.isAtBottom() }
            .distinctUntilChanged()
            .collect { atBottom ->
                coordinator.following = atBottom
                coordinator.showJumpButton = !atBottom
            }
    }

    // 新内容到达时才尝试跟随。
    //
    // 判据是「不在滚动中」而不是「用户没在拖」——`isScrollInProgress` 分不清
    // 用户拖动和程序化滚动，拿它当「用户在拖」会自己把自己锁死：
    // 一开始自动滚 → isScrollInProgress 变 true → 判定为用户在拖 → 不再滚。
    // 这个更笨的规则绝对安全：真在拖时不滚（正确），程序化滚动中也不滚（本该如此）。
    // 代价是流式追加滞后一拍，但列表末尾有哨兵项保证永远有可滚余量，结论不会被藏住。
    LaunchedEffect(contentKey) {
        if (coordinator.canFollow(state)) {
            val last = state.layoutInfo.totalItemsCount - 1
            if (last >= 0) state.scrollToItem(last)
        }
    }

    return coordinator
}

/** 最后一项是否完整可见。列表空时视为贴底（否则空会话会显示「回到底部」按钮）。 */
private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0) return true
    return info.visibleItemsInfo.any { it.index == lastIndex }
}
