package com.mistakebook.data.chat

import com.mistakebook.domain.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文组装测试。
 *
 * 这里的每条规则都对应一个**已经踩过或能想出来的坑**，
 * 尤其注意「反向用例」——只测正确路径的话，
 * 「丢半轮」「吞掉最新一轮」「省略条数算错」都能悄悄通过。
 */
class ChatContextAssemblerTest {

    private fun user(id: Long, text: String) =
        OutgoingMessage(id = id, role = ChatRole.USER, text = text)

    private fun assistant(id: Long, text: String) =
        OutgoingMessage(id = id, role = ChatRole.ASSISTANT, text = text)

    // ------------------------------------------------------------ 分轮

    @Test
    fun `一轮是一条用户消息加其后的助手消息`() {
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(
                user(1, "问1"), assistant(2, "答1"),
                user(3, "问2"), assistant(4, "答2"),
                assistant(5, "追问"), assistant(6, "续答")
            )
        )
        // 2 轮：第 2 轮的 assistant 连续两条都归到 user(3) 那一轮
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L, 2L), rounds[0].map { it.id })
        assertEquals(listOf(3L, 4L, 5L, 6L), rounds[1].map { it.id })
    }

    @Test
    fun `开头是孤立的助手消息也自成一轮`() {
        // 脏数据兜底：不该崩，也不该把它和后面的用户消息并成一轮。
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(assistant(1, "残留"), user(2, "问"))
        )
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L), rounds[0].map { it.id })
    }

    @Test
    fun `连续两条用户消息是各自一轮`() {
        // 用户连发两条没等回答就发了下一条——这是常见操作，不能并轮。
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(user(1, "问1"), user(2, "问2"), assistant(3, "答"))
        )
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L), rounds[0].map { it.id })
        assertEquals(listOf(2L, 3L), rounds[1].map { it.id })
    }

    // ------------------------------------------------------------ 基本保留

    @Test
    fun `短历史全部保留且顺序不变`() {
        val result = ChatContextAssembler.assemble(
            systemPrompt = "sys",
            questionContext = "题目",
            history = listOf(user(1, "问1"), assistant(2, "答1"), user(3, "问2"), assistant(4, "答2"))
        )
        assertEquals(0, result.omittedCount)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.messages.map { it.id })
    }

    @Test
    fun `历史里的 SYSTEM 消息被剔除`() {
        // SYSTEM 是给模型看的上下文标记，不该作为「用户说过的话」再发一遍。
        val result = ChatContextAssembler.assemble(
            systemPrompt = "sys",
            questionContext = "题目",
            history = listOf(
                user(1, "问1"), assistant(2, "答1"),
                OutgoingMessage(id = 3, role = ChatRole.SYSTEM, text = "已省略 2 条早期对话")
            )
        )
        assertEquals(listOf(1L, 2L), result.messages.map { it.id })
    }

    // ------------------------------------------------------------ 轮数上限

    @Test
    fun `超过轮数上限时丢最旧的整轮`() {
        // 8 轮上限：造 10 轮，前 2 轮应被丢，且**不能丢半轮**。
        val history = (1L..20L).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        assertEquals(8, result.keptRounds)
        assertEquals(2, result.omittedCount)
        // 保留的是最近 8 轮 = id 5..20，且 id=5 这一轮的用户消息必须还在
        assertEquals(16, result.messages.size)
        assertTrue(result.messages.any { it.id == 5L })
        assertTrue(result.messages.none { it.id == 1L || it.id == 2L })
    }

    @Test
    fun `丢的是整轮不是半轮`() {
        // 反向用例：只丢助手消息、留着用户问题是最糟的——模型会对着半个问题发挥。
        val history = (1L..20L).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        val keptIds = result.messages.map { it.id }.toSet()
        // 每个保留的用户消息后面必须跟着它的回答
        keptIds.filter { it % 2 == 1L }.forEach { userId ->
            assertTrue("用户 $userId 的回答被丢了", keptIds.contains(userId + 1))
        }
    }

    // ------------------------------------------------------------ 字符预算

    @Test
    fun `超字符预算时丢最旧的整轮`() {
        // 每轮 2 * 1904 = 3808 字符。2 轮 7616 装得下，3 轮 11424 装不下。
        val big = "x".repeat(1900)
        val history = listOf(
            user(1, "问1$big"), assistant(2, "答1$big"),
            user(3, "问3$big"), assistant(4, "答4$big"),
            user(5, "问5$big"), assistant(6, "答6$big")
        )
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        assertEquals(2, result.keptRounds)
        assertEquals(1, result.omittedCount)
        assertEquals(listOf(3L, 4L, 5L, 6L), result.messages.map { it.id })
    }

    @Test
    fun `附件文本计入字符预算`() {
        // 反向用例：只算正文不算附件，预算会形同虚设。
        val sameHistory: List<OutgoingMessage> = listOf(
            OutgoingMessage(id = 1, role = ChatRole.USER, text = "问", attachmentText = "y".repeat(5000)),
            assistant(2, "答"),
            OutgoingMessage(id = 3, role = ChatRole.USER, text = "问3", attachmentText = "y".repeat(5000)),
            assistant(4, "答4")
        )
        val withAttachment = ChatContextAssembler.assemble("sys", "题目", sameHistory)
        // 每轮 5000+ 字符，2 轮就超 8000 -> 只留最新一轮
        assertEquals(1, withAttachment.keptRounds)
        assertEquals(1, withAttachment.omittedCount)

        // 同样两条消息，附件文本清空后正文只有几个字 -> 一轮都不该丢。
        // 两条断言放一起，才能证明「丢弃是被附件文本撑出来的」而不是别的原因。
        val withoutAttachment = ChatContextAssembler.assemble(
            "sys", "题目", sameHistory.map { it.copy(attachmentText = "") }
        )
        assertEquals(0, withoutAttachment.omittedCount)
        assertEquals(2, withoutAttachment.keptRounds)
    }

    // ------------------------------------------------------------ 最新一轮必留

    @Test
    fun `最新一轮永远保留即使超预算`() {
        // 灾难场景：用户刚发的问题被自己发出去的历史挤掉。
        val huge = "x".repeat(20_000)
        val history = listOf(
            user(1, "旧问$huge"), assistant(2, "旧答$huge"),
            user(3, "旧问3$huge"), assistant(4, "旧答4$huge")
        )
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        assertEquals(1, result.keptRounds)
        assertEquals(listOf(3L, 4L), result.messages.map { it.id })
    }

    @Test
    fun `本次新输入无论多大都发送且计入省略条数`() {
        val huge = "x".repeat(50_000)
        val result = ChatContextAssembler.assemble(
            systemPrompt = "sys",
            questionContext = "题目",
            history = listOf(user(1, "旧问"), assistant(2, "旧答")),
            pendingText = "新的超长问题$huge"
        )
        // 最后一轮 = 新输入，id = -1
        assertEquals(listOf(-1L), result.messages.map { it.id })
        assertEquals(1, result.omittedCount)
    }

    @Test
    fun `只有新输入没有历史时省略数为零`() {
        val result = ChatContextAssembler.assemble("sys", "题目", emptyList(), pendingText = "你好")
        assertEquals(0, result.omittedCount)
        // 只有新输入这一轮，没有历史可丢
        assertEquals(1, result.keptRounds)
        assertEquals(listOf(-1L), result.messages.map { it.id })
    }

    @Test
    fun `全空输入不崩`() {
        val result = ChatContextAssembler.assemble("sys", "题目", emptyList())
        assertEquals(0, result.messages.size)
        assertEquals(0, result.omittedCount)
    }

    // ------------------------------------------------------------ 图片张数

    @Test
    fun `图片张数超上限时保留最近的`() {
        val img = listOf("data:image/jpeg;base64,AAA")
        val history = listOf(
            OutgoingMessage(1, ChatRole.USER, "问1", images = img),
            assistant(2, "答1"),
            OutgoingMessage(3, ChatRole.USER, "问3", images = img),
            assistant(4, "答4"),
            OutgoingMessage(5, ChatRole.USER, "问5", images = img),
            assistant(6, "答6")
        )
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        val withImages = result.messages.filter { it.images.isNotEmpty() }
        assertEquals(2, withImages.size)
        // 输出按时间排序；这里要验的是「拿到名额的是最近两条」，
        // 不是名额的分配顺序——所以比集合。
        assertEquals(setOf(3L, 5L), withImages.map { it.id }.toSet())
        // 反向用例：最旧那条必须没有图（正序遍历会让它抢光名额）
        assertTrue(result.messages.none { it.id == 1L && it.images.isNotEmpty() })
    }

    @Test
    fun `一条消息里多张图也只占一个名额但只取前几张`() {
        val img = listOf("data:image/jpeg;base64,A", "data:image/jpeg;base64,B", "data:image/jpeg;base64,C")
        val history = listOf(
            OutgoingMessage(1, ChatRole.USER, "问1", images = img),
            assistant(2, "答2"),
            OutgoingMessage(3, ChatRole.USER, "问3", images = listOf("data:image/jpeg;base64,D"))
        )
        val result = ChatContextAssembler.assemble("sys", "题目", history)
        val first = result.messages.first { it.id == 1L }
        // 上限 2：最新那条占 1 个，旧消息只剩 1 个名额
        assertEquals(1, first.images.size)
        assertEquals("data:image/jpeg;base64,A", first.images.first())
    }

    // ------------------------------------------------------------ 省略条数

    @Test
    fun `省略数在有无新输入两种情况下都正确`() {
        val history = (1L..20L).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        // 10 轮历史，轮数上限 8 -> 丢 2 轮
        val withoutPending = ChatContextAssembler.assemble("sys", "题目", history)
        assertEquals(10, withoutPending.keptRounds + withoutPending.omittedCount)
        assertEquals(2, withoutPending.omittedCount)
        assertEquals(8, withoutPending.keptRounds)

        val withPending = ChatContextAssembler.assemble("sys", "题目", history, pendingText = "新问题")
        // 11 轮（10 历史 + 新输入），上限 8 -> 只留最新 8 轮，
        // 其中 1 轮是新输入，所以历史保留 7 轮、丢 3 轮。
        // 关键：新输入占了一个名额，省略数要跟着涨，否则 UI 会少报一条。
        assertEquals(8, withPending.keptRounds)
        assertEquals(3, withPending.omittedCount)
        assertEquals(listOf(-1L), withPending.messages.takeLast(1).map { it.id })
    }
}
