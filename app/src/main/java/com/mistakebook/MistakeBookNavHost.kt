@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mistakebook

import android.net.Uri
import androidx.navigation.NavHostController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.capture.CaptureScreen
import com.mistakebook.ui.crop.CropScreen
import com.mistakebook.ui.detail.DetailScreen
import com.mistakebook.ui.edit.EditScreen
import com.mistakebook.ui.edit.QuestionEditScreen
import com.mistakebook.ui.home.HomeScreen
import com.mistakebook.ui.importpdf.PdfImportScreen
import com.mistakebook.ui.notebook.NotebookScreen
import com.mistakebook.ui.print.PrintScreen
import com.mistakebook.ui.progress.ProgressScreen
import com.mistakebook.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CAPTURE = "capture"
    const val CROP = "crop"
    const val PROGRESS = "progress"
    const val EDIT = "edit"
    const val DETAIL = "detail"
    const val PRINT = "print"
    const val PDF_IMPORT = "pdfImport"
    const val QUESTION_EDIT = "questionEdit"
    const val NOTEBOOKS = "notebooks"

    fun crop(imagePath: String): String = "$CROP/${Uri.encode(imagePath)}"

    fun progress(taskId: Long): String = "$PROGRESS/$taskId"

    fun edit(taskId: Long, index: Int = 0): String = "$EDIT/$taskId?index=$index"

    fun detail(questionId: Long): String = "$DETAIL/$questionId"

    fun questionEdit(questionId: Long): String = "$QUESTION_EDIT/$questionId"
}

@androidx.compose.runtime.Composable
fun MistakeBookNavHost(
    container: AppContainer,
    filterDue: Boolean = false,
    navController: NavHostController = rememberNavController()
) {
    // 错题本页选中后回传给首页筛选。用 remember 而不是导航参数：
    // 筛选状态本来就归 HomeViewModel 管，多带一层参数只会让两边状态不同步。
    var homeNotebookPick by remember { mutableStateOf<Long?>(null) }

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                filterDue = filterDue,
                pickedNotebookId = homeNotebookPick,
                onNotebookPicked = { homeNotebookPick = it },
                onAddByPhoto = { navController.navigate(Routes.CAPTURE) },
                onImportPdf = { navController.navigate(Routes.PDF_IMPORT) },
                onCropImage = { path ->
                    container.cropSourceIsGallery = true
                    navController.navigate(Routes.crop(path))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPrint = { navController.navigate(Routes.PRINT) },
                onOpenNotebooks = { navController.navigate(Routes.NOTEBOOKS) },
                onOpenQuestion = { id -> navController.navigate(Routes.detail(id)) }
            )
        }

        composable(Routes.NOTEBOOKS) {
            NotebookScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onSelected = { notebookId ->
                    // 选中后回首页并切到该错题本筛选，用户能立刻看到筛选生效
                    navController.popBackStack()
                    homeNotebookPick = notebookId
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { navController.popBackStack() })
        }

        composable(Routes.CAPTURE) {
            CaptureScreen(
                container = container,
                onCropped = { cropPath ->
                    navController.navigate(Routes.crop(cropPath)) {
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "${Routes.CROP}/{imagePath}",
            arguments = listOf(navArgument("imagePath") { type = NavType.StringType })
        ) { entry ->
            val recrop = container.recropping
            CropScreen(
                container = container,
                imagePath = entry.arguments?.getString("imagePath").orEmpty(),
                recropOnly = recrop,
                onRecropped = { newPath ->
                    container.recroppedImagePath = newPath
                    container.recropping = false
                    navController.popBackStack()
                },
                onConfirmed = { taskIds ->
                    container.recropping = false
                    navController.navigate(Routes.progress(taskIds.first())) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBack = {
                    container.recropping = false
                    // 用户中途退出裁剪页时必须清掉来源标记，
                    // 否则下一次拍照提交会被错标成「相册导入」
                    container.cropSourceIsGallery = false
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "${Routes.PROGRESS}/{taskId}",
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { entry ->
            val taskId = entry.arguments?.getLong("taskId") ?: 0L
            ProgressScreen(
                container = container,
                taskId = taskId,
                onBack = { navController.popBackStack() },
                onEdit = { id ->
                    navController.navigate(Routes.edit(id)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = "${Routes.EDIT}/{taskId}?index={index}",
            arguments = listOf(
                navArgument("taskId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            EditScreen(
                container = container,
                taskId = entry.arguments?.getLong("taskId") ?: 0L,
                initialIndex = entry.arguments?.getInt("index") ?: 0,
                onRecrop = { path -> navController.navigate(Routes.crop(path)) },
                onBack = { navController.popBackStack() },
                onSaved = { questionId ->
                    navController.navigate(Routes.detail(questionId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = "${Routes.DETAIL}/{questionId}",
            arguments = listOf(navArgument("questionId") { type = NavType.LongType })
        ) { entry ->
            DetailScreen(
                container = container,
                questionId = entry.arguments?.getLong("questionId") ?: 0L,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.questionEdit(id)) },
                onRecrop = { path -> navController.navigate(Routes.crop(path)) },
                // 重新识别提交成功后跳进度页，让用户看着它跑完。
                // 之前调的是 onBack()，用户点完就「回到主页」，完全不知道任务已经提交了。
                onReRecognize = { taskId ->
                    navController.navigate(Routes.progress(taskId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = "${Routes.QUESTION_EDIT}/{questionId}",
            arguments = listOf(navArgument("questionId") { type = NavType.LongType })
        ) { entry ->
            QuestionEditScreen(
                container = container,
                questionId = entry.arguments?.getLong("questionId") ?: 0L,
                onRecrop = { path -> navController.navigate(Routes.crop(path)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PRINT) {
            PrintScreen(container = container, onBack = { navController.popBackStack() })
        }

        composable(Routes.PDF_IMPORT) {
            PdfImportScreen(
                container = container,
                onStarted = {
                    val ids = container.lastImportTaskIds
                    if (ids.isNotEmpty()) {
                        navController.navigate(Routes.progress(ids.first())) {
                            popUpTo(Routes.HOME)
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
