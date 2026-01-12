package com.example.chineselearning

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class WordItem(val hanzi: String, val pinyin: String, val meaning: String, val imageName: String? = null, val isPersonal: Boolean = false)
data class Topic(val topic: String, val words: List<WordItem>)

// Data classes for PvP
data class PvPPlayer(val id: String = "", val name: String = "", val avatar: String = "", var score: Int = 0, var currentIdx: Int = 0)
data class PvPRoom(val roomId: String = "", val p1: PvPPlayer? = null, val p2: PvPPlayer? = null, val questionIndices: List<Int> = listOf(), var status: String = "matching", val betAmount: Int = 0)

// Chat Data Class
data class ChatMessage(val senderId: String = "", val message: String = "", val timestamp: Long = 0)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var allTopics: List<Topic> = listOf()
    private var commonPhrases: List<Topic> = listOf()
    private var personalWords: MutableList<WordItem> = mutableListOf()
    private var currentWordList: List<WordItem> = listOf()
    private var currentIndex = 0
    private var drawingView: DrawingView? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var quizCount = 1
    private val totalQuiz = 50
    private var isSpinning = false
    private var currentStreak = 0
    private var maxStreakInSession = 0
    private var energy = 10
    private var spinMediaPlayer: MediaPlayer? = null
    private val scoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scoreRunnable: Runnable? = null

    private var myId: String = ""
    private var userUid: String = "" 
    private var myAvatarPetId: String = ""
    private var tempImagePath: String? = null
    private var ivPreviewRef: ImageView? = null
    private var isPinyinVisible: Boolean = true
    
    // Study Time tracking
    private var sessionStartTime: Long = 0

    // PvP Variables
    private var currentPvPRoomId: String? = null
    private var isP1: Boolean = false
    private var pvpQuestions: List<WordItem> = listOf()
    private var pvpCurrentIndex = 0
    private var pvpMyScore = 0
    private var pvpOpponentScore = 0
    private var matchingTimer: CountDownTimer? = null
    private var battleTimer: CountDownTimer? = null
    private var pvpRoomListener: ValueEventListener? = null
    private var pvpBetAmount = 0

    // Map to store last read timestamp for each friend
    private val friendLastReadTimestamps = mutableMapOf<String, Long>()

    private val petList = listOf(
        "mouse_adward", "cow_adward", "tiger_adward", "cat_adward",
        "dragon_adward", "snake_adward", "hourse_adward", "goat_adward",
        "monkey_adward", "chicken_adward", "dog_adward", "pig_adward"
    )

    private var database: FirebaseDatabase? = null

    enum class QuizMode { NORMAL, REVIEW_LEARNED, REVIEW_WRONG, REVIEW_STARRED, PERSONAL }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImagePath != null) {
            ivPreviewRef?.setImageBitmap(BitmapFactory.decodeFile(tempImagePath))
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            ivPreviewRef?.setImageURI(it)
            tempImagePath = it.toString()
        }
    }

    private fun String.removeAccents(): String {
        val temp = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        return "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(temp, "").replace("đ", "d").replace("Đ", "D")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        sessionStartTime = System.currentTimeMillis()

        try {
            FirebaseApp.initializeApp(this)
            database = FirebaseDatabase.getInstance()
        } catch (e: Exception) { e.printStackTrace() }

        val prefs = getSharedPreferences("LearningPrefs", Context.MODE_PRIVATE)
        myId = prefs.getString("online_id", "") ?: ""
        
        userUid = prefs.getString("user_uid", "") ?: ""
        if (userUid.isEmpty()) {
            if (myId.isNotEmpty()) {
                userUid = myId 
            } else {
                userUid = UUID.randomUUID().toString()
            }
            prefs.edit().putString("user_uid", userUid).apply()
        }

        if (myId.isEmpty()) {
            myId = "USER_${(1000..9999).random()}"
            prefs.edit().putString("online_id", myId).apply()
        }

        loadVocabularyFromJson()
        loadCommonPhrasesFromJson()
        loadPersonalWords()
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        
        database?.getReference("users")?.child(userUid)?.child("avatarPetId")?.get()?.addOnSuccessListener {
            myAvatarPetId = it.value?.toString() ?: ""
            syncMyProgressToCloud()
        } ?: syncMyProgressToCloud()

        listenForFriendRequests()
        listenForGlobalMessages()
        createNotificationChannel()

        val layoutMenu = findViewById<View>(R.id.layoutMenu)
        val containerLearn = findViewById<View>(R.id.containerLearn)
        val containerQuiz = findViewById<View>(R.id.containerQuiz)
        val containerShop = findViewById<View>(R.id.containerShop)
        val containerPhrases = findViewById<View>(R.id.containerPhrases)
        val containerProgress = findViewById<View>(R.id.containerProgress)
        val containerSocial = findViewById<View>(R.id.containerSocial)
        val containerPersonal = findViewById<View>(R.id.containerPersonal)
        val containerPvP = findViewById<View>(R.id.containerPvP)

        val tvHanzi = findViewById<TextView>(R.id.tvHanzi)
        val tvPinyin = findViewById<TextView>(R.id.tvPinyin)
        val imgWord = findViewById<ImageView>(R.id.imgWordDesc)
        val spinner = findViewById<Spinner>(R.id.spinnerTopics)
        drawingView = findViewById(R.id.drawingView)

        val tvPhraseHanzi = findViewById<TextView>(R.id.tvPhraseHanzi)
        val tvPhrasePinyin = findViewById<TextView>(R.id.tvPhrasePinyin)
        val tvPhraseMeaning = findViewById<TextView>(R.id.tvPhraseMeaning)

        // Setup Search
        val etSearch = findViewById<EditText>(R.id.etSearchWord)
        findViewById<ImageView>(R.id.btnSearch)?.setOnClickListener { performSearch(etSearch?.text.toString(), tvHanzi, tvPinyin, imgWord) }
        etSearch?.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(etSearch.text.toString(), tvHanzi, tvPinyin, imgWord); true } else false }

        drawingView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                scoreRunnable?.let { scoreHandler.removeCallbacks(it) }
                scoreRunnable = Runnable {
                    val score = drawingView?.calculateScore() ?: 0
                    val tvScore = findViewById<TextView>(R.id.tvDrawScore)
                    tvScore?.visibility = View.VISIBLE; tvScore?.text = "ĐIỂM: $score"
                    if (score >= 80) { showFloatingText(drawingView!!, "Tuyệt vời! 🌟"); updateGold(2) }
                }
                scoreHandler.postDelayed(scoreRunnable!!, 2000)
            }
            false
        }

        setupShop()
        updateGoldDisplay()

        findViewById<Button>(R.id.btnBackFromLearn)?.setOnClickListener {
            containerLearn?.visibility = View.GONE
            layoutMenu?.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnPreviousLearn)?.setOnClickListener {
            if (currentWordList.isNotEmpty()) {
                currentIndex = if (currentIndex > 0) currentIndex - 1 else currentWordList.size - 1
                updateLearnUI(tvHanzi, tvPinyin, imgWord)
            }
        }
        findViewById<Button>(R.id.btnClear)?.setOnClickListener {
            drawingView?.clear()
            findViewById<TextView>(R.id.tvDrawScore)?.text = "Điểm: --"
        }
        findViewById<Button>(R.id.btnNextLearn)?.setOnClickListener {
            if (currentWordList.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % currentWordList.size
                updateLearnUI(tvHanzi, tvPinyin, imgWord)
            }
        }

        // Stroke Order Logic
        val webViewStroke = findViewById<WebView>(R.id.webViewStroke)
        webViewStroke?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webViewStroke?.webViewClient = WebViewClient()
        webViewStroke?.setBackgroundColor(0) 

        findViewById<ImageButton>(R.id.btnPlayStroke)?.setOnClickListener {
            val hanzi = tvHanzi?.text?.toString() ?: ""
            if (hanzi.isNotEmpty()) {
                if (webViewStroke?.visibility == View.VISIBLE) {
                    webViewStroke.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(300).withEndAction { 
                        webViewStroke.visibility = View.GONE 
                        drawingView?.setShowGuideText(true) // Hiện lại khung chữ xám khi tắt hoạt ảnh
                    }.start()
                } else {
                    webViewStroke?.visibility = View.VISIBLE
                    webViewStroke?.alpha = 0f
                    webViewStroke?.scaleX = 0.5f
                    webViewStroke?.scaleY = 0.5f
                    webViewStroke?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.alpha(1f)?.setDuration(500)?.start()
                    drawingView?.clear() 
                    drawingView?.setShowGuideText(false) // Ẩn khung chữ xám tĩnh để chạy hoạt ảnh
                    showStrokeAnimation(webViewStroke, hanzi)
                }
            }
        }

        // PHRASES Mode
        findViewById<Button>(R.id.btnCommonPhrases)?.setOnClickListener {
            if (commonPhrases.isNotEmpty()) { layoutMenu?.visibility = View.GONE; containerPhrases?.visibility = View.VISIBLE; setupPhraseSpinner(findViewById(R.id.spinnerPhrases), tvPhraseHanzi, tvPhrasePinyin, tvPhraseMeaning) }
        }
        findViewById<Button>(R.id.btnBackFromPhrases)?.setOnClickListener { containerPhrases?.visibility = View.GONE; layoutMenu?.visibility = View.VISIBLE }
        findViewById<Button>(R.id.btnPrevPhrase)?.setOnClickListener { if (currentWordList.isNotEmpty()) { currentIndex = if (currentIndex > 0) currentIndex - 1 else currentWordList.size - 1; updatePhraseUI(tvPhraseHanzi, tvPhrasePinyin, tvPhraseMeaning) } }
        findViewById<Button>(R.id.btnNextPhrase)?.setOnClickListener { if (currentWordList.isNotEmpty()) { currentIndex = (currentIndex + 1) % currentWordList.size; updatePhraseUI(tvPhraseHanzi, tvPhrasePinyin, tvPhraseMeaning) } }
        findViewById<ImageView>(R.id.ivSpeakPhrase)?.setOnClickListener { val text = tvPhraseHanzi?.text.toString(); if (text.isNotEmpty()) speakChinese(text) }

        // Personal Library
        findViewById<Button>(R.id.btnPersonalLibrary)?.setOnClickListener { layoutMenu?.visibility = View.GONE; containerPersonal?.visibility = View.VISIBLE; updatePersonalListView() }
        findViewById<Button>(R.id.btnBackFromPersonal)?.setOnClickListener { containerPersonal?.visibility = View.GONE; layoutMenu?.visibility = View.VISIBLE }
        findViewById<Button>(R.id.btnAddPersonalWord)?.setOnClickListener { showAddOrEditPersonalWordDialog() }
        findViewById<Button>(R.id.btnQuizPersonal)?.setOnClickListener {
            if (personalWords.size >= 4) {
                quizCount = 1; currentStreak = 0; layoutMenu?.visibility = View.GONE; containerPersonal?.visibility = View.GONE; containerQuiz?.visibility = View.VISIBLE; setupQuiz(QuizMode.PERSONAL)
            } else Toast.makeText(this, "Cần ít nhất 4 từ trong thư viện!", Toast.LENGTH_SHORT).show()
        }

        // Navigation Menu
        findViewById<Button>(R.id.btnGoToLearn)?.setOnClickListener {
            if (allTopics.isNotEmpty()) {
                layoutMenu?.visibility = View.GONE
                containerLearn?.visibility = View.VISIBLE
                setupTopicSpinner(spinner, tvHanzi, tvPinyin, imgWord)
            }
        }
        findViewById<Button>(R.id.btnGoToQuiz)?.setOnClickListener { if (commonPhrases.isNotEmpty() || allTopics.isNotEmpty()) { quizCount = 1; currentStreak = 0; layoutMenu?.visibility = View.GONE; containerQuiz?.visibility = View.VISIBLE; setupQuiz(QuizMode.NORMAL) } }
        findViewById<Button>(R.id.btnReviewStarred)?.setOnClickListener { val s = getStarredWords(); if (s.size >= 4) { quizCount = 1; currentStreak = 0; layoutMenu?.visibility = View.GONE; containerQuiz?.visibility = View.VISIBLE; setupQuiz(QuizMode.REVIEW_STARRED) } else Toast.makeText(this, "Cần ít nhất 4 mục đã đánh dấu!", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnViewProgress)?.setOnClickListener { layoutMenu?.visibility = View.GONE; containerProgress?.visibility = View.VISIBLE; updateProgressUI() }
        findViewById<Button>(R.id.btnBackFromProgress)?.setOnClickListener { containerProgress?.visibility = View.GONE; layoutMenu?.visibility = View.VISIBLE }
        
        findViewById<Button>(R.id.btnSocial)?.setOnClickListener { 
            layoutMenu?.visibility = View.GONE
            containerSocial?.visibility = View.VISIBLE
            updateSocialUI()
        }
        
        findViewById<Button>(R.id.btnBackFromSocial)?.setOnClickListener { containerSocial?.visibility = View.GONE; layoutMenu?.visibility = View.VISIBLE }
        findViewById<Button>(R.id.btnAddFriend)?.setOnClickListener { showAddFriendDialog() }
        findViewById<Button>(R.id.btnEditId)?.setOnClickListener { showEditIdDialog() }
        findViewById<View>(R.id.btnChangeAvatar)?.setOnClickListener { showAvatarPetSelectionDialog() }
        
        findViewById<Button>(R.id.btnViewAllUsers)?.setOnClickListener { 
            showPasswordDialog { showAllUsersDialog() } 
        }

        findViewById<ImageView>(R.id.ivShop)?.setOnClickListener { layoutMenu?.visibility = View.GONE; containerShop?.visibility = View.VISIBLE; refreshPetGrid(); updateGoldDisplay() }
        findViewById<ImageView>(R.id.ivSpeak)?.setOnClickListener { val text = tvHanzi?.text.toString(); if (text.isNotEmpty() && text != "?") speakChinese(text) }
        findViewById<ImageView>(R.id.ivMic)?.setOnClickListener { checkPermissionAndStartSpeech(tvHanzi?.text.toString()) }
        findViewById<Button>(R.id.btnBackFromQuiz)?.setOnClickListener { containerQuiz?.visibility = View.GONE; layoutMenu?.visibility = View.VISIBLE }

        findViewById<ImageView>(R.id.btnTogglePinyin)?.setOnClickListener {
            isPinyinVisible = !isPinyinVisible
            val tvP = findViewById<TextView>(R.id.tvQuizPinyin)
            val btn = it as ImageView
            if (isPinyinVisible) {
                tvP?.visibility = View.VISIBLE
                btn.setImageResource(android.R.drawable.ic_menu_view)
            } else {
                tvP?.visibility = View.INVISIBLE
                btn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
        }

        // PvP Navigation
        findViewById<Button>(R.id.btnPvP)?.setOnClickListener {
            layoutMenu?.visibility = View.GONE
            containerPvP?.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutPvPBet)?.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutPvPMatching)?.visibility = View.GONE
            findViewById<View>(R.id.layoutPvPBattle)?.visibility = View.GONE
        }
        findViewById<Button>(R.id.btnBackFromBet)?.setOnClickListener {
            containerPvP?.visibility = View.GONE
            layoutMenu?.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnCancelMatching)?.setOnClickListener {
            stopPvPMatchmaking()
            findViewById<View>(R.id.layoutPvPMatching)?.visibility = View.GONE
            findViewById<View>(R.id.layoutPvPBet)?.visibility = View.VISIBLE
        }

        // Bet Buttons
        findViewById<Button>(R.id.btnBet10)?.setOnClickListener { tryStartMatching(10) }
        findViewById<Button>(R.id.btnBet50)?.setOnClickListener { tryStartMatching(50) }
        findViewById<Button>(R.id.btnBet100)?.setOnClickListener { tryStartMatching(100) }
    }

    private fun showPasswordDialog(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nhập mật khẩu"
            gravity = android.view.Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle("Yêu cầu mật khẩu")
            .setMessage("Vui lòng nhập mật khẩu để xem danh sách ID")
            .setView(input)
            .setPositiveButton("Xác nhận") { _, _ ->
                if (input.text.toString() == "21092025") {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Mật khẩu không chính xác!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun tryStartMatching(amount: Int) {
        val currentGold = getSharedPreferences("LearningPrefs", 0).getInt("gold_count", 0)
        if (currentGold < amount) {
            Toast.makeText(this, "Không đủ vàng!", Toast.LENGTH_SHORT).show()
            return
        }
        pvpBetAmount = amount
        findViewById<View>(R.id.layoutPvPBet)?.visibility = View.GONE
        startPvPMatchmaking()
    }

    // --- PVP LOGIC ---
    private fun startPvPMatchmaking() {
        val matchingLayout = findViewById<View>(R.id.layoutPvPMatching)
        matchingLayout?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvMatchStatus)?.text = "ĐANG TÌM ĐỐI THỦ ($pvpBetAmount 💰)..."

        val queueRef = database?.getReference("pvp_queue")?.child("bet_$pvpBetAmount") ?: return
        val myQueueEntry = queueRef.child(userUid)
        
        var seconds = 0
        matchingTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(ms: Long) {
                seconds++
                findViewById<TextView>(R.id.tvMatchTimer)?.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
            }
            override fun onFinish() { stopPvPMatchmaking(); Toast.makeText(this@MainActivity, "Không tìm thấy đối thủ!", Toast.LENGTH_SHORT).show(); findViewById<View>(R.id.layoutPvPMatching)?.visibility = View.GONE; findViewById<View>(R.id.layoutPvPBet)?.visibility = View.VISIBLE }
        }.start()

        queueRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var opponentFound = false
                for (child in snapshot.children) {
                    val oppUid = child.key ?: ""
                    if (oppUid != userUid) {
                        opponentFound = true
                        val roomId = "ROOM_${System.currentTimeMillis()}"
                        val allWords = (allTopics.flatMap { it.words } + commonPhrases.flatMap { it.words }).distinctBy { it.hanzi }
                        val indices = (0 until allWords.size).shuffled().take(10)
                        
                        val p1 = PvPPlayer(oppUid, oppUid, "") 
                        val p2 = PvPPlayer(userUid, myId, myAvatarPetId)
                        val room = PvPRoom(roomId, p1, p2, indices, "battle", pvpBetAmount)
                        
                        database?.getReference("pvp_rooms")?.child(roomId)?.setValue(room)
                        queueRef.child(oppUid).child("roomId").setValue(roomId)
                        queueRef.child(userUid).removeValue()
                        
                        joinPvPRoom(roomId, false)
                        break
                    }
                }
                if (!opponentFound) {
                    myQueueEntry.setValue(mapOf("id" to userUid, "roomId" to ""))
                    myQueueEntry.child("roomId").addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            val rId = s.value?.toString() ?: ""
                            if (rId.isNotEmpty()) {
                                myQueueEntry.removeValue()
                                joinPvPRoom(rId, true)
                                myQueueEntry.child("roomId").removeEventListener(this)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun stopPvPMatchmaking() {
        matchingTimer?.cancel()
        database?.getReference("pvp_queue")?.child("bet_$pvpBetAmount")?.child(userUid)?.removeValue()
    }

    private fun joinPvPRoom(roomId: String, isPlayer1: Boolean) {
        currentPvPRoomId = roomId
        isP1 = isPlayer1
        matchingTimer?.cancel()
        findViewById<View>(R.id.layoutPvPMatching)?.visibility = View.GONE
        findViewById<View>(R.id.layoutPvPBattle)?.visibility = View.VISIBLE
        
        pvpMyScore = 0
        pvpOpponentScore = 0
        pvpCurrentIndex = 0
        pvpQuestions = listOf()
        
        battleTimer?.cancel()
        battleTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(ms: Long) { findViewById<TextView>(R.id.tvBattleTimer)?.text = "Thời gian: ${ms / 1000}s" }
            override fun onFinish() { pvpCurrentIndex = 10; updatePvPProgressInFirebase(); setupPvPUI() }
        }.start()

        val roomRef = database?.getReference("pvp_rooms")?.child(roomId) ?: return
        pvpRoomListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(PvPRoom::class.java) ?: return
                pvpBetAmount = room.betAmount 
                if (pvpQuestions.isEmpty() && room.questionIndices.isNotEmpty()) {
                    val allWords = (allTopics.flatMap { it.words } + commonPhrases.flatMap { it.words }).distinctBy { it.hanzi }
                    pvpQuestions = room.questionIndices.map { allWords[it] }
                    setupPvPUI()
                }
                val opp = if (isP1) room.p2 else room.p1
                pvpOpponentScore = opp?.score ?: 0
                findViewById<TextView>(R.id.tvPvPOpponentScore)?.text = "$pvpOpponentScore/10"
                findViewById<TextView>(R.id.tvPvPOpponentName)?.text = opp?.name ?: opp?.id ?: "ĐỐI THỦ"
                if (opp?.avatar?.isNotEmpty() == true) {
                    val resId = resources.getIdentifier(opp.avatar, "drawable", packageName)
                    if (resId != 0) findViewById<ImageView>(R.id.ivPvPOpponentAvatar)?.setImageResource(resId)
                }
                val myFin = if (isP1) room.p1?.currentIdx ?: 0 else room.p2?.currentIdx ?: 0
                val oppFin = if (isP1) room.p2?.currentIdx ?: 0 else room.p1?.currentIdx ?: 0
                if (myFin >= 10 && oppFin >= 10) { battleTimer?.cancel(); roomRef.removeEventListener(this); showPvPResult() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        roomRef.addValueEventListener(pvpRoomListener!!)
        val myPath = if (isP1) "p1" else "p2"
        roomRef.child(myPath).child("avatar").setValue(myAvatarPetId)
        roomRef.child(myPath).child("name").setValue(myId)
    }

    private fun setupPvPUI() {
        if (pvpCurrentIndex >= pvpQuestions.size) {
            findViewById<TextView>(R.id.tvPvPHanzi)?.text = "ĐỢI ĐỐI THỦ..."
            findViewById<TextView>(R.id.tvPvPPinyin)?.text = ""
            listOf(R.id.btnPvPOpt1, R.id.btnPvPOpt2, R.id.btnPvPOpt3, R.id.btnPvPOpt4).forEach { findViewById<Button>(it)?.visibility = View.INVISIBLE }
            return
        }
        listOf(R.id.btnPvPOpt1, R.id.btnPvPOpt2, R.id.btnPvPOpt3, R.id.btnPvPOpt4).forEach { findViewById<Button>(it)?.visibility = View.VISIBLE }
        val q = pvpQuestions[pvpCurrentIndex]
        findViewById<TextView>(R.id.tvPvPHanzi)?.text = q.hanzi
        findViewById<TextView>(R.id.tvPvPPinyin)?.text = q.pinyin
        findViewById<TextView>(R.id.tvPvPMyScore)?.text = "$pvpMyScore/10"
        val allWords = (allTopics.flatMap { it.words } + commonPhrases.flatMap { it.words }).distinctBy { it.hanzi }
        val opts = allWords.filter { it.hanzi != q.hanzi }.shuffled().take(3).toMutableList()
        opts.add(q); opts.shuffle()
        val btns = listOf(findViewById<Button>(R.id.btnPvPOpt1), findViewById<Button>(R.id.btnPvPOpt2), findViewById<Button>(R.id.btnPvPOpt3), findViewById<Button>(R.id.btnPvPOpt4))
        btns.forEachIndexed { i, b -> 
            b?.setBackgroundColor(android.graphics.Color.WHITE)
            b?.text = opts[i].meaning
            b?.isEnabled = true
            b?.setOnClickListener { 
                btns.forEach { it?.isEnabled = false }
                if (opts[i].hanzi == q.hanzi) { 
                    b.setBackgroundColor(android.graphics.Color.GREEN)
                    pvpMyScore++ 
                } else { 
                    b.setBackgroundColor(android.graphics.Color.RED)
                    // Highlight correct answer in green
                    btns.forEachIndexed { index, button -> if (opts[index].hanzi == q.hanzi) button?.setBackgroundColor(android.graphics.Color.GREEN) }
                }
                pvpCurrentIndex++
                updatePvPProgressInFirebase()
                b.postDelayed({ setupPvPUI() }, 800) 
            } 
        }
    }

    private fun updatePvPProgressInFirebase() {
        val roomId = currentPvPRoomId ?: return
        val path = if (isP1) "p1" else "p2"
        val updates = mapOf("score" to pvpMyScore, "currentIdx" to pvpCurrentIndex)
        database?.getReference("pvp_rooms")?.child(roomId)?.child(path)?.updateChildren(updates)
    }

    private fun showPvPResult() {
        if (isFinishing) return
        val win = pvpMyScore > pvpOpponentScore
        val draw = pvpMyScore == pvpOpponentScore
        val reward = if (win) pvpBetAmount else if (draw) 0 else -pvpBetAmount
        val msg = when {
            win -> "BẠN ĐÃ THẮNG! 🏆\nNhận được $reward vàng"
            draw -> "HÒA NHAU! 🤝\nKhông mất vàng"
            else -> "BẠN ĐÃ THUA! 💀\nBị trừ ${-reward} vàng"
        }
        updateGold(reward)
        AlertDialog.Builder(this).setTitle("Kết quả thi đấu").setMessage(msg).setCancelable(false).setPositiveButton("OK") { _, _ -> findViewById<View>(R.id.containerPvP)?.visibility = View.GONE; findViewById<View>(R.id.layoutMenu)?.visibility = View.VISIBLE; pvpQuestions = listOf(); currentPvPRoomId = null }.show()
    }

    private fun loadPersonalWords() { personalWords = Gson().fromJson(getSharedPreferences("PersonalData", MODE_PRIVATE).getString("words", "[]"), object : TypeToken<MutableList<WordItem>>() {}.type) }
    private fun savePersonalWords() { getSharedPreferences("PersonalData", MODE_PRIVATE).edit().putString("words", Gson().toJson(personalWords)).apply() }
    private fun updatePersonalListView() { 
        val adapter = object : ArrayAdapter<WordItem>(this, R.layout.item_personal_word, personalWords) { 
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View { 
                val v = cv ?: LayoutInflater.from(context).inflate(R.layout.item_personal_word, parent, false)
                val item = getItem(p)!!
                v.findViewById<TextView>(R.id.tvItemHanzi).text = item.hanzi
                v.findViewById<TextView>(R.id.tvItemMeaning).text = item.meaning
                val iv = v.findViewById<ImageView>(R.id.ivItemImage)
                if (item.imageName != null) {
                    if (item.imageName.startsWith("content://")) iv.setImageURI(Uri.parse(item.imageName))
                    else iv.setImageBitmap(BitmapFactory.decodeFile(item.imageName))
                } else { iv.setImageResource(android.R.drawable.ic_menu_gallery) }
                return v 
            } 
        }
        val lv = findViewById<ListView>(R.id.lvPersonalWords)
        lv.adapter = adapter 
        lv.setOnItemClickListener { _, _, position, _ -> showAddOrEditPersonalWordDialog(position) }
        lv.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this).setTitle("Xóa từ").setMessage("Bạn có chắc muốn xóa từ này?").setPositiveButton("Xóa") { _, _ -> personalWords.removeAt(position); savePersonalWords(); updatePersonalListView() }.setNegativeButton("Hủy", null).show()
            true
        }
    }
    
    private fun showAddOrEditPersonalWordDialog(editPosition: Int = -1) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_add_personal_word, null)
        val etH = v.findViewById<EditText>(R.id.etPersonalHanzi); val etM = v.findViewById<EditText>(R.id.etPersonalMeaning)
        val ivP = v.findViewById<ImageView>(R.id.ivPersonalPreview)
        ivPreviewRef = ivP; tempImagePath = null
        if (editPosition != -1) {
            val item = personalWords[editPosition]
            etH.setText(item.hanzi); etM.setText(item.meaning); tempImagePath = item.imageName
            if (tempImagePath != null) {
                if (tempImagePath!!.startsWith("content://")) ivP.setImageURI(Uri.parse(tempImagePath))
                else ivP.setImageBitmap(BitmapFactory.decodeFile(tempImagePath))
            }
        }
        ivP.setOnClickListener {
            val options = arrayOf("Chụp ảnh mới", "Chọn từ thư viện")
            AlertDialog.Builder(this).setTitle("Thêm hình ảnh").setItems(options) { _, which ->
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
                    } else {
                        val file = File(getExternalFilesDir(null), "personal_${System.currentTimeMillis()}.jpg")
                        tempImagePath = file.absolutePath
                        takePhotoLauncher.launch(FileProvider.getUriForFile(this, "com.example.chineselearning.provider", file))
                    }
                } else { pickImageLauncher.launch("image/*") }
            }.show()
        }
        AlertDialog.Builder(this).setTitle(if (editPosition == -1) "Thêm từ mới" else "Sửa từ vựng").setView(v).setPositiveButton("Lưu") { _, _ -> val h = etH.text.toString().trim(); val m = etM.text.toString().trim(); if (h.isNotEmpty() && m.isNotEmpty()) { val newItem = WordItem(h, "", m, tempImagePath, true); if (editPosition == -1) personalWords.add(newItem) else personalWords[editPosition] = newItem; savePersonalWords(); updatePersonalListView() } }.setNegativeButton("Hủy", null).show()
    }

    private fun initSpeechRecognizer() { if (SpeechRecognizer.isRecognitionAvailable(this)) { speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this); speechRecognizer?.setRecognitionListener(object : RecognitionListener { override fun onReadyForSpeech(p: Bundle?) { Toast.makeText(this@MainActivity, "Đang lắng nghe...", Toast.LENGTH_SHORT).show() }; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(r: Float) {}; override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onError(e: Int) { Toast.makeText(this@MainActivity, "Lỗi nhận diện", Toast.LENGTH_SHORT).show() }; override fun onResults(r: Bundle?) { val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (!m.isNullOrEmpty()) calculateSpeakScore(m[0], findViewById<TextView>(R.id.tvHanzi)?.text.toString()) }; override fun onPartialResults(p: Bundle?) {}; override fun onEvent(ev: Int, p: Bundle?) {} }) } }
    private fun checkPermissionAndStartSpeech(t: String) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101) else startSpeechToText() }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) { super.onRequestPermissionsResult(rc, p, gr); if (rc == 101 && gr.isNotEmpty() && gr[0] == PackageManager.PERMISSION_GRANTED) startSpeechToText() }
    private fun startSpeechToText() { try { speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); putExtra(RecognizerIntent.EXTRA_PROMPT, "Hãy đọc từ vựng") }) } catch (e: Exception) { Toast.makeText(this, "Lỗi micro", Toast.LENGTH_SHORT).show() } }
    
    private fun calculateSpeakScore(recognized: String, target: String) {
        val tv = findViewById<TextView>(R.id.tvSpeakScore)
        tv?.visibility = View.VISIBLE
        
        val s1 = recognized.trim().lowercase()
        val s2 = target.trim().lowercase()
        
        if (s1 == s2) {
            tv?.text = "Phát âm: 100đ 🌟"
            tv?.setTextColor(android.graphics.Color.parseColor("#00B894"))
            updateGold(2)
            return
        }

        // Tính toán độ tương đồng (Simple Jaccard-like ratio for Hanzi)
        val s1Chars = s1.toSet()
        val s2Chars = s2.toSet()
        val intersection = s1Chars.intersect(s2Chars).size
        val union = s1Chars.union(s2Chars).size
        val ratio = if (union == 0) 0f else intersection.toFloat() / union
        
        // Map ratio (0..1) vào khoảng điểm (40..100)
        val score = (40 + (ratio * 60)).toInt().coerceIn(40, 100)
        
        tv?.text = "Phát âm: ${score}đ (Nghe như: $recognized)"
        if (score >= 80) {
            tv?.setTextColor(android.graphics.Color.parseColor("#00B894"))
            updateGold(1)
        } else {
            tv?.setTextColor(android.graphics.Color.parseColor("#FF7675"))
        }
    }

    private fun performSearch(q: String, tvH: TextView?, tvP: TextView?, img: ImageView?) { if (q.isBlank()) return; val qL = q.trim().lowercase(); val qL_accents = qL.removeAccents() ; for (topic in allTopics) { val i = topic.words.indexOfFirst { it.hanzi.lowercase().contains(qL) || it.meaning.lowercase().contains(qL) || it.meaning.lowercase().removeAccents().contains(qL_accents) || it.pinyin.lowercase().contains(qL) }; if (i != -1) { findViewById<Spinner>(R.id.spinnerTopics)?.setSelection(allTopics.indexOf(topic)); currentWordList = topic.words; currentIndex = i; updateLearnUI(tvH, tvP, img); return } } }
    private fun showAvatarPetSelectionDialog() { val u = petList.filter { getSharedPreferences("GameData", MODE_PRIVATE).getBoolean(it, false) }; if (u.isEmpty()) return; val gv = GridView(this).apply { numColumns = 3; verticalSpacing = 10; horizontalSpacing = 10; setPadding(20, 20, 20, 20) }; val d = AlertDialog.Builder(this).setTitle("Chọn Linh vật").setView(gv).setNegativeButton("Hủy", null).create(); gv.adapter = object : ArrayAdapter<String>(this, 0, u) { override fun getView(p: Int, cv: View?, parent: ViewGroup): View { return ImageView(context).apply { layoutParams = AbsListView.LayoutParams(200, 200); scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(resources.getIdentifier(getItem(p)!!, "drawable", packageName)); setPadding(10, 10, 10, 10) } } }; gv.setOnItemClickListener { _, _, p, _ -> myAvatarPetId = u[p]; findViewById<ImageView>(R.id.ivMyAvatar)?.setImageResource(resources.getIdentifier(myAvatarPetId, "drawable", packageName)); syncMyProgressToCloud() ; d.dismiss() }; d.show() }
    private fun setupPhraseSpinner(sp: Spinner?, tvH: TextView?, tvP: TextView?, tvM: TextView?) { if (commonPhrases.isEmpty() || sp == null) return; sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, commonPhrases.map { it.topic }); sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) { currentWordList = commonPhrases[pos].words; currentIndex = 0; updatePhraseUI(tvH, tvP, tvM) }; override fun onNothingSelected(p0: AdapterView<*>?) {} } }
    private fun updatePhraseUI(tvH: TextView?, tvP: TextView?, tvM: TextView?) { if (currentWordList.isEmpty()) return; val item = currentWordList[currentIndex]; tvH?.text = item.hanzi; tvP?.text = item.pinyin; tvM?.text = item.meaning; findViewById<ProgressBar>(R.id.phraseProgress)?.apply { max = currentWordList.size; progress = currentIndex + 1 }; findViewById<TextView>(R.id.tvPhraseProgressCount)?.text = "${currentIndex + 1} / ${currentWordList.size}"; val btn = findViewById<ImageButton>(R.id.btnStarPhrase); btn?.setImageResource(if (getSharedPreferences("MarkedWords", MODE_PRIVATE).getStringSet("stars", setOf())?.contains(item.hanzi) == true) R.drawable.btn_star_big_on else R.drawable.btn_star_big_off); btn?.setOnClickListener { toggleStar(item, btn) } }
    
    private fun setupQuiz(mode: QuizMode) {
        val sL = when (mode) { QuizMode.PERSONAL -> personalWords; QuizMode.NORMAL -> (allTopics.flatMap { it.words } + commonPhrases.flatMap { it.words }).distinctBy { it.hanzi }; QuizMode.REVIEW_WRONG -> getWrongWords(); QuizMode.REVIEW_STARRED -> getStarredWords() ; else -> allTopics.flatMap { it.words } }
        val pp = getSharedPreferences("LearningProgress", 0)
        val fL = if (mode == QuizMode.PERSONAL) sL else sL.filter { pp.getInt(it.hanzi, 0) < 5 }
        if (fL.size < 4) { findViewById<View>(R.id.containerQuiz)?.visibility = View.GONE; findViewById<View>(R.id.layoutMenu)?.visibility = View.VISIBLE; return }
        
        val q = fL.random()
        findViewById<TextView>(R.id.tvQuizHanzi)?.text = q.hanzi
        val tvP = findViewById<TextView>(R.id.tvQuizPinyin)
        tvP?.text = q.pinyin
        tvP?.visibility = if (isPinyinVisible) View.VISIBLE else View.INVISIBLE
        findViewById<ImageView>(R.id.btnTogglePinyin)?.setImageResource(if (isPinyinVisible) android.R.drawable.ic_menu_view else android.R.drawable.ic_menu_close_clear_cancel)

        val iv = findViewById<ImageView>(R.id.ivQuizImage)
        if (q.isPersonal && q.imageName != null) {
            iv.visibility = View.VISIBLE
            if (q.imageName.startsWith("content://")) iv.setImageURI(Uri.parse(q.imageName)) else iv.setImageBitmap(BitmapFactory.decodeFile(q.imageName))
        } else iv.visibility = View.GONE
        findViewById<ImageView>(R.id.ivQuizSpeak)?.setOnClickListener { speakChinese(q.hanzi) }
        val cT = if (mode != QuizMode.NORMAL) fL.size else totalQuiz
        findViewById<TextView>(R.id.tvQuizStatus)?.text = "Câu hỏi: $quizCount/$cT"
        val ts = findViewById<TextView>(R.id.tvCurrentCombo); ts?.visibility = if (currentStreak >= 2) View.VISIBLE else View.INVISIBLE; ts?.text = "CHUỖI: $currentStreak 🔥"
        val dp = (allTopics + commonPhrases).flatMap { it.words } + personalWords
        val opts = dp.filter { it.hanzi != q.hanzi }.shuffled().take(3).toMutableList(); opts.add(q); opts.shuffle()
        val btns = listOf(findViewById<Button>(R.id.btnOpt1), findViewById<Button>(R.id.btnOpt2), findViewById<Button>(R.id.btnOpt3), findViewById<Button>(R.id.btnOpt4))
        btns.forEachIndexed { i, b -> 
            b?.setBackgroundColor(android.graphics.Color.WHITE)
            b?.isEnabled = true
            b?.text = opts[i].meaning
            b?.setOnClickListener { 
                btns.forEach { it?.isEnabled = false }
                if (opts[i].hanzi == q.hanzi) { 
                    b.setBackgroundColor(android.graphics.Color.GREEN)
                    speakChinese(q.hanzi)
                    currentStreak++
                    updateGold(1)
                    recordCorrectAnswer(q.hanzi)
                    b.postDelayed({ if (quizCount < cT) { quizCount++; setupQuiz(mode) } else showFinalResult() }, 1000) 
                } else { 
                    b.setBackgroundColor(android.graphics.Color.RED)
                    // Highlight correct answer in green
                    btns.forEachIndexed { index, button -> if (opts[index].hanzi == q.hanzi) button?.setBackgroundColor(android.graphics.Color.GREEN) }
                    currentStreak = 0
                    updateGold(-2)
                    energy--
                    if (energy <= 0) { 
                        findViewById<View>(R.id.containerQuiz)?.visibility = View.GONE
                        findViewById<View>(R.id.layoutMenu)?.visibility = View.VISIBLE 
                    } else b.postDelayed({ if (quizCount < cT) { quizCount++; setupQuiz(mode) } else showFinalResult() }, 1000) 
                } 
            } 
        }
    }

    private fun showFinalResult() { AlertDialog.Builder(this).setTitle("Hoàn thành").setMessage("Chuỗi: $maxStreakInSession 🔥").setPositiveButton("OK") { _, _ -> findViewById<View>(R.id.containerQuiz)?.visibility = View.GONE; findViewById<View>(R.id.layoutMenu)?.visibility = View.VISIBLE }.show() }
    private fun getWrongWords(): List<WordItem> { val s = getSharedPreferences("LearningPrefs", 0).getStringSet("wrong_set", setOf()) ?: setOf(); return (allTopics + commonPhrases).flatMap { it.words }.filter { s.contains(it.hanzi) } }
    private fun getStarredWords(): List<WordItem> { val s = getSharedPreferences("MarkedWords", MODE_PRIVATE).getStringSet("stars", setOf()) ?: setOf(); return (allTopics + commonPhrases).flatMap { it.words }.filter { s.contains(it.hanzi) } }
    private fun toggleStar(item: WordItem, btn: ImageButton) { val p = getSharedPreferences("MarkedWords", MODE_PRIVATE); val s = p.getStringSet("stars", setOf())?.toMutableSet() ?: mutableSetOf(); if (s.contains(item.hanzi)) { s.remove(item.hanzi); btn.setImageResource(R.drawable.btn_star_big_off) } else { s.add(item.hanzi); btn.setImageResource(R.drawable.btn_star_big_on) }; p.edit().putStringSet("stars", s).apply() }
    private fun recordCorrectAnswer(h: String) { val p = getSharedPreferences("LearningProgress", 0); p.edit().putInt(h, p.getInt(h, 0) + 1).apply(); syncMyProgressToCloud() }
    
    private fun updateStudyTime() {
        val now = System.currentTimeMillis()
        val duration = now - sessionStartTime
        if (duration > 0) {
            val params = getSharedPreferences("LearningPrefs", Context.MODE_PRIVATE)
            val total = params.getLong("total_study_time_ms", 0)
            params.edit().putLong("total_study_time_ms", total + duration).apply()
            sessionStartTime = now
        }
    }

    override fun onPause() {
        super.onPause()
        updateStudyTime()
    }

    private fun formatDuration(ms: Long): String {
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) "${hours}h ${minutes}p" else "${minutes}p"
    }

    private fun formatLastActive(ms: Long): String {
        if (ms == 0L) return "Chưa rõ"
        val diff = System.currentTimeMillis() - ms
        val minutes = diff / (1000 * 60)
        return when {
            minutes < 1 -> "Vừa xong"
            minutes < 60 -> "$minutes phút trước"
            minutes < 1440 -> "${minutes / 60} giờ trước"
            else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun syncMyProgressToCloud() {
        if (userUid.isEmpty()) return
        updateStudyTime()
        val pp = getSharedPreferences("LearningProgress", 0)
        val params = getSharedPreferences("LearningPrefs", 0)
        val v = allTopics.flatMap { it.words }.distinctBy { it.hanzi }
        val ph = commonPhrases.flatMap { it.words }.distinctBy { it.hanzi }
        val wordCount = v.count { pp.getInt(it.hanzi, 0) >= 5 }
        val phraseCount = ph.count { pp.getInt(it.hanzi, 0) >= 5 }
        
        val userMap = mapOf(
            "uid" to userUid,
            "name" to myId,
            "progress" to (wordCount + phraseCount),
            "wordCount" to wordCount,
            "phraseCount" to phraseCount,
            "gold" to params.getInt("gold_count", 0),
            "avatarPetId" to myAvatarPetId,
            "totalStudyTime" to params.getLong("total_study_time_ms", 0),
            "lastUpdate" to System.currentTimeMillis()
        )
        database?.let { db -> db.getReference("users").child(userUid).updateChildren(userMap) }
    }

    private fun updateProgressUI() { val pp = getSharedPreferences("LearningProgress", 0); val v = allTopics.flatMap { it.words }.distinctBy { it.hanzi }; val ph = commonPhrases.flatMap { it.words }.distinctBy { it.hanzi }; val mv = v.filter { pp.getInt(it.hanzi, 0) >= 5 }; val mph = ph.filter { pp.getInt(it.hanzi, 0) >= 5 }; findViewById<TextView>(R.id.tvWordsStat)?.text = "${mv.size}/${v.size} Đã thuộc"; findViewById<TextView>(R.id.tvPhrasesStat)?.text = "${mph.size}/${ph.size} Đã thuộc"; findViewById<ProgressBar>(R.id.progressCircleWords)?.progress = if (v.isNotEmpty()) mv.size * 100 / v.size else 0; findViewById<ProgressBar>(R.id.progressCirclePhrases)?.progress = if (ph.isNotEmpty()) mph.size * 100 / ph.size else 0; findViewById<TextView>(R.id.tvPercentWords)?.text = "${if (v.isNotEmpty()) mv.size * 100 / v.size else 0}%"; findViewById<TextView>(R.id.tvPercentPhrases)?.text = "${if (ph.isNotEmpty()) mph.size * 100 / ph.size else 0}%"; findViewById<ListView>(R.id.lvMasteredItems)?.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, (mv + mph).map { "${it.hanzi} (${it.pinyin}): ${it.meaning}" }) }
    
    private fun updateSocialUI() {
        findViewById<TextView>(R.id.tvMyUserId)?.text = myId
        database?.let { db ->
            db.getReference("users").child(userUid).child("avatarPetId").addListenerForSingleValueEvent(object : ValueEventListener { 
                override fun onDataChange(s: DataSnapshot) { 
                    myAvatarPetId = s.value?.toString() ?: ""
                    if (myAvatarPetId.isNotEmpty()) { 
                        val id = resources.getIdentifier(myAvatarPetId, "drawable", packageName)
                        if (id != 0) findViewById<ImageView>(R.id.ivMyAvatar)?.setImageResource(id) 
                    } 
                } 
                override fun onCancelled(e: DatabaseError) {} 
            })

            db.getReference("users").child(userUid).child("friends").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(friendsSnapshot: DataSnapshot) {
                    val uids = mutableListOf<String>()
                    friendsSnapshot.children.forEach { it.key?.let { uid -> uids.add(uid) } }
                    if (!uids.contains(userUid)) uids.add(userUid) 

                    val userList = mutableListOf<DataSnapshot>()
                    var count = 0
                    if (uids.isEmpty()) { displayFriendsRanking(userList); return }

                    uids.forEach { uid ->
                        db.getReference("users").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                if (snapshot.exists()) {
                                    userList.add(snapshot)
                                    // Load chat status for this friend
                                    db.getReference("users").child(userUid).child("chat_status").child(uid).child("lastReadTimestamp").get()
                                        .addOnSuccessListener {
                                            val lastRead = it.value?.toString()?.toLongOrNull() ?: 0L
                                            friendLastReadTimestamps[uid] = lastRead
                                        }
                                }
                                count++
                                if (count == uids.size) {
                                    val sortedList = userList.sortedByDescending { it.child("progress").value?.toString()?.toInt() ?: 0 }
                                    displayFriendsRanking(sortedList)
                                }
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    private fun displayFriendsRanking(rL: List<DataSnapshot>) {
        val lv = findViewById<ListView>(R.id.lvFriendsRanking)
        lv.adapter = object : ArrayAdapter<DataSnapshot>(this@MainActivity, R.layout.item_ranking, rL) {
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: LayoutInflater.from(context).inflate(R.layout.item_ranking, parent, false)
                val item = getItem(p)!!
                val friendUid = item.key ?: ""

                v.findViewById<TextView>(R.id.tvRankName).text = item.child("name").value?.toString() ?: friendUid
                v.findViewById<TextView>(R.id.tvRankProg).text = "Đã thuộc: ${item.child("progress").value ?: 0}"
                val petId = item.child("avatarPetId").value?.toString() ?: ""
                if (petId.isNotEmpty()) { val resId = context.resources.getIdentifier(petId, "drawable", context.packageName); if (resId != 0) v.findViewById<ImageView>(R.id.ivRankAvatar).setImageResource(resId) }

                // Calculate and display unread messages
                val tvUnreadCount = v.findViewById<TextView>(R.id.tvUnreadCount)
                if (friendUid.isNotEmpty() && friendUid != userUid) {
                    val chatId = if (userUid < friendUid) "${userUid}_$friendUid" else "${friendUid}_$userUid"
                    val chatRef = database?.getReference("chats")?.child(chatId)
                    val lastReadTimestamp = friendLastReadTimestamps[friendUid] ?: 0L

                    chatRef?.orderByChild("timestamp")?.startAt(lastReadTimestamp.toDouble())?.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val unreadCount = snapshot.childrenCount.toInt()
                            if (unreadCount > 0) {
                                tvUnreadCount.visibility = View.VISIBLE
                                tvUnreadCount.text = unreadCount.toString()
                            } else {
                                tvUnreadCount.visibility = View.GONE
                            }
                        }
                        override fun onCancelled(error: DatabaseError) { tvUnreadCount.visibility = View.GONE } // Hide if error occurs
                    })
                } else {
                    tvUnreadCount.visibility = View.GONE
                }

                return v
            }
        }
        lv.setOnItemClickListener { _, _, position, _ ->
            val userSnap = rL[position]
            val userId = userSnap.key ?: ""
            if (userId == userUid) return@setOnItemClickListener

            val options = arrayOf("Xem thông tin", "Nhắn tin", "Xoá bạn")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Tuỳ chọn: ${userSnap.child("name").value ?: userId}")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showUserResultDialog(userSnap)
                        1 -> showChatDialog(userId, userSnap.child("name").value?.toString() ?: userId)
                        2 -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Xác nhận")
                                .setMessage("Bạn có muốn xoá ${userSnap.child("name").value ?: userId} khỏi danh sách bạn bè?")
                                .setPositiveButton("Xoá") { _, _ ->
                                    database?.let { db ->
                                        val updates = HashMap<String, Any?>()
                                        updates["/users/$userUid/friends/$userId"] = null
                                        updates["/users/$userId/friends/$userUid"] = null
                                        db.getReference().updateChildren(updates)
                                            .addOnSuccessListener { Toast.makeText(this@MainActivity, "Đã xoá bạn!", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    }
                }.show()
        }
    }

    private fun showUserResultDialog(snapshot: DataSnapshot) {
        val n = snapshot.child("name").value?.toString() ?: snapshot.key ?: "Unknown"
        val targetUid = snapshot.key ?: ""
        val w = snapshot.child("wordCount").value?.toString() ?: "0"
        val ph = snapshot.child("phraseCount").value?.toString() ?: "0"
        val g = snapshot.child("gold").value?.toString() ?: "0"
        val petId = snapshot.child("avatarPetId").value?.toString() ?: ""
        
        val totalTimeMs = snapshot.child("totalStudyTime").value?.toString()?.toLongOrNull() ?: 0L
        val lastActiveMs = snapshot.child("lastUpdate").value?.toString()?.toLongOrNull() ?: 0L
        
        val timeStr = formatDuration(totalTimeMs)
        val activeStr = formatLastActive(lastActiveMs)

        val v = LayoutInflater.from(this).inflate(R.layout.dialog_user_detail, null)
        if (petId.isNotEmpty()) {
            val resId = resources.getIdentifier(petId, "drawable", packageName)
            if (resId != 0) v.findViewById<ImageView>(R.id.ivDetailAvatar).setImageResource(resId)
        }
        
        // Hiển thị thêm ID trong thông tin chi tiết
        v.findViewById<TextView>(R.id.tvDetailInfo).text = "🆔 ID: $n\n📚 Từ vựng: $w\n📝 Mẫu câu: $ph\n💰 Vàng: $g\n⏱️ Thời gian học: $timeStr\n📅 Hoạt động: $activeStr"
        
        val btnAdd = v.findViewById<Button>(R.id.btnAddFriendDetail)
        
        // Kiểm tra xem đã là bạn chưa hoặc là chính mình
        if (targetUid != userUid) {
            database?.getReference("users")?.child(userUid)?.child("friends")?.child(targetUid)
                ?.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        if (!s.exists()) {
                            btnAdd.visibility = View.VISIBLE
                            btnAdd.setOnClickListener {
                                sendFriendRequest(targetUid)
                                btnAdd.visibility = View.GONE
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        AlertDialog.Builder(this).setTitle("Thông tin người dùng").setView(v).setPositiveButton("OK", null).show()
    }

    private fun showEditIdDialog() {
        val i = EditText(this).apply { setText(myId); hint = "ID mới" }
        AlertDialog.Builder(this).setTitle("Đổi ID").setView(i).setPositiveButton("Cập nhật") { _, _ ->
            val nI = i.text.toString().trim()
            if (nI.isNotEmpty() && nI != myId) {
                database?.getReference("users")?.orderByChild("name")?.equalTo(nI)
                    ?.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (snapshot.exists()) {
                                Toast.makeText(this@MainActivity, "ID này đã có người sử dụng!", Toast.LENGTH_SHORT).show()
                            } else {
                                myId = nI
                                getSharedPreferences("LearningPrefs", 0).edit().putString("online_id", myId).apply()
                                syncMyProgressToCloud()
                                updateSocialUI()
                                Toast.makeText(this@MainActivity, "Đã đổi ID thành công!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }
        }.setNegativeButton("Hủy", null).show()
    }
    
    private fun showAddFriendDialog() {
        val i = EditText(this).apply { hint = "Nhập đúng ID bạn bè" }
        AlertDialog.Builder(this)
            .setTitle("Thêm bạn")
            .setView(i)
            .setPositiveButton("Kết nối") { _, _ ->
                val friendIdInput = i.text.toString().trim()
                if (friendIdInput.isNotEmpty() && friendIdInput != myId) {
                    database?.let { db ->
                        db.getReference("users").orderByChild("name").equalTo(friendIdInput)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    if (snapshot.exists()) {
                                        val friendUid = snapshot.children.first().key ?: ""
                                        sendFriendRequest(friendUid)
                                    } else {
                                        db.getReference("users").child(friendIdInput).get().addOnSuccessListener { s2 ->
                                            if (s2.exists()) {
                                                sendFriendRequest(friendIdInput)
                                            } else {
                                                Toast.makeText(this@MainActivity, "Không tìm thấy người dùng này!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    Toast.makeText(this@MainActivity, "Lỗi kết nối Firebase!", Toast.LENGTH_SHORT).show()
                                }
                            })
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun sendFriendRequest(friendUid: String) {
        if (friendUid == userUid) return
        database?.getReference("friend_requests")?.child(friendUid)?.child(userUid)?.setValue("pending")
            ?.addOnSuccessListener { 
                Toast.makeText(this, "Đã gửi lời mời kết bạn! Đang chờ chấp nhận.", Toast.LENGTH_SHORT).show() 
            }
            ?.addOnFailureListener { 
                Toast.makeText(this, "Không thể gửi lời mời!", Toast.LENGTH_SHORT).show() 
            }
    }

    private fun listenForFriendRequests() {
        if (userUid.isEmpty()) return
        database?.getReference("friend_requests")?.child(userUid)
            ?.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        snapshot.children.forEach { request ->
                            val senderUid = request.key ?: return@forEach
                            val status = request.value.toString()
                            if (status == "pending") {
                                showFriendRequestDialog(senderUid)
                            }
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showFriendRequestDialog(senderUid: String) {
        database?.getReference("users")?.child(senderUid)?.child("name")?.get()?.addOnSuccessListener {
            val senderName = it.value?.toString() ?: "Người dùng lạ"
            AlertDialog.Builder(this)
                .setTitle("Lời mời kết bạn")
                .setMessage("$senderName muốn kết bạn với bạn. Bạn có đồng ý không?")
                .setCancelable(false)
                .setPositiveButton("Đồng ý") { _, _ ->
                    acceptFriendRequest(senderUid)
                }
                .setNegativeButton("Từ chối") { _, _ ->
                    declineFriendRequest(senderUid)
                }
                .show()
        }
    }

    private fun acceptFriendRequest(senderUid: String) {
        val updates = HashMap<String, Any?>()
        updates["/users/$userUid/friends/$senderUid"] = true
        updates["/users/$senderUid/friends/$userUid"] = true
        updates["/friend_requests/$userUid/$senderUid"] = null
        
        database?.getReference()?.updateChildren(updates)?.addOnSuccessListener {
            Toast.makeText(this, "Đã trở thành bạn bè!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun declineFriendRequest(senderUid: String) {
        database?.getReference("friend_requests")?.child(userUid)?.child(senderUid)?.removeValue()
            ?.addOnSuccessListener {
                Toast.makeText(this, "Đã từ chối lời mời.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showAllUsersDialog() {
        database?.getReference("users")?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val allUsers = mutableListOf<DataSnapshot>()
                snapshot.children.forEach { allUsers.add(it) }
                
                val lv = ListView(this@MainActivity)
                lv.adapter = object : ArrayAdapter<DataSnapshot>(this@MainActivity, R.layout.item_ranking, allUsers) {
                    override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                        val v = cv ?: LayoutInflater.from(context).inflate(R.layout.item_ranking, parent, false)
                        val item = getItem(p)!!
                        v.findViewById<TextView>(R.id.tvRankName).text = item.child("name").value?.toString() ?: "Unknown"
                        v.findViewById<TextView>(R.id.tvRankProg).text = "Progress: ${item.child("progress").value ?: 0}"
                        val petId = item.child("avatarPetId").value?.toString() ?: ""
                        if (petId.isNotEmpty()) {
                            val resId = context.resources.getIdentifier(petId, "drawable", context.packageName)
                            if (resId != 0) v.findViewById<ImageView>(R.id.ivRankAvatar).setImageResource(resId)
                        }
                        return v
                    }
                }
                
                lv.setOnItemClickListener { _, _, position, _ ->
                    showUserResultDialog(allUsers[position])
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Danh sách người dùng app")
                    .setView(lv)
                    .setPositiveButton("Đóng", null)
                    .show()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // --- Chat Logic ---
    private var isChatDialogOpen = false
    private var activeChatTargetId = ""

    private fun showChatDialog(targetUid: String, targetName: String) {
        isChatDialogOpen = true
        activeChatTargetId = targetUid
        
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_chat, null)
        val lv = v.findViewById<ListView>(R.id.lvChatMessages)
        val et = v.findViewById<EditText>(R.id.etChatMessage)
        val btn = v.findViewById<ImageButton>(R.id.btnSendChat)
        
        val chatId = if (userUid < targetUid) "${userUid}_$targetUid" else "${targetUid}_$userUid"
        val chatRef = database?.getReference("chats")?.child(chatId)
        val messages = mutableListOf<ChatMessage>()
        
        val adapter = object : ArrayAdapter<ChatMessage>(this, R.layout.item_chat_message, messages) {
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val view = cv ?: LayoutInflater.from(context).inflate(R.layout.item_chat_message, parent, false)
                val msg = getItem(p)!!
                val isMe = msg.senderId == userUid
                
                val container = view.findViewById<LinearLayout>(R.id.layoutMessageContainer)
                val bubble = view.findViewById<LinearLayout>(R.id.layoutMessageBubble)
                val tvMsg = view.findViewById<TextView>(R.id.tvChatMessage)
                val tvTime = view.findViewById<TextView>(R.id.tvChatTime)
                
                tvMsg.text = msg.message
                tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                
                container.gravity = if (isMe) Gravity.END else Gravity.START
                bubble.setBackgroundResource(if (isMe) R.drawable.chat_bubble_me else R.drawable.chat_bubble_other)
                tvMsg.setTextColor(if (isMe) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                
                return view
            }
        }
        lv.adapter = adapter
        
        val listener = chatRef?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messages.clear()
                snapshot.children.forEach { child ->
                    child.getValue(ChatMessage::class.java)?.let { messages.add(it) }
                }
                adapter.notifyDataSetChanged()
                lv.post { lv.setSelection(adapter.count - 1) }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        btn.setOnClickListener {
            val text = et.text.toString().trim()
            if (text.isNotEmpty()) {
                val msg = ChatMessage(userUid, text, System.currentTimeMillis())
                chatRef?.push()?.setValue(msg)
                et.setText("")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Chat với $targetName")
            .setView(v)
            .setOnDismissListener { 
                chatRef?.removeEventListener(listener!!)
                isChatDialogOpen = false
                activeChatTargetId = ""
                // Update last read timestamp when the chat dialog is closed
                val currentTimestamp = System.currentTimeMillis()
                database?.getReference("users")?.child(userUid)?.child("chat_status")?.child(targetUid)?.child("lastReadTimestamp")?.setValue(currentTimestamp)
                friendLastReadTimestamps[targetUid] = currentTimestamp
            }
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun listenForGlobalMessages() {
        if (userUid.isEmpty()) return
        database?.getReference("chats")?.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (snapshot.key?.contains(userUid) == true) {
                    snapshot.ref.limitToLast(1).addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            val lastMsg = s.children.lastOrNull()?.getValue(ChatMessage::class.java)
                            if (lastMsg != null && lastMsg.senderId != userUid) {
                                // Kiểm tra xem người dùng có đang mở dialog chat với người gửi này không
                                if (!isChatDialogOpen || activeChatTargetId != lastMsg.senderId) {
                                    showChatNotification(lastMsg)
                                }
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showChatNotification(msg: ChatMessage) {
        database?.getReference("users")?.child(msg.senderId)?.child("name")?.get()?.addOnSuccessListener {
            val senderName = it.value?.toString() ?: "Bạn bè"
            val builder = NotificationCompat.Builder(this, "chat_channel")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(senderName)
                .setContentText(msg.message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(1001, builder.build())
            
            // Chơi âm thanh thông báo
            try {
                val notification = Uri.parse("android.resource://$packageName/${R.raw.chest_open}") // Tạm dùng âm thanh rương
                val r = android.media.RingtoneManager.getRingtone(applicationContext, notification)
                r.play()
            } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Chat Channel"
            val descriptionText = "Notifications for chat messages"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("chat_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateGold(a: Int) { val p = getSharedPreferences("LearningPrefs", 0); p.edit().putInt("gold_count", (p.getInt("gold_count", 0) + a).coerceAtLeast(0)).apply(); updateGoldDisplay(); syncMyProgressToCloud() }
    private fun updateGoldDisplay() { val g = getSharedPreferences("LearningPrefs", 0).getInt("gold_count", 0); findViewById<TextView>(R.id.tvGoldCount)?.text = ": $g"; findViewById<TextView>(R.id.tvGoldInShop)?.text = "VÀNG: $g"; findViewById<TextView>(R.id.tvQuizGoldCount)?.text = "$g" }
    private fun setupShop() { findViewById<Button>(R.id.btnSpin)?.setOnClickListener { startSpinning() }; findViewById<Button>(R.id.btnBackFromShop)?.setOnClickListener { findViewById<View>(R.id.containerShop)?.visibility = View.GONE; findViewById<View>(R.id.layoutMenu)?.visibility = View.VISIBLE } }
    private fun startSpinning() { if (isSpinning) return; if (getSharedPreferences("LearningPrefs", 0).getInt("gold_count", 0) >= 20) { isSpinning = true; updateGold(-20); spinMediaPlayer = MediaPlayer.create(this, R.raw.chest_open); spinMediaPlayer?.start(); findViewById<ImageView>(R.id.ivWheel)?.animate()?.rotationBy(1800f + (0..360).random())?.setDuration(4000)?.withEndAction { isSpinning = false; val p = petList.random(); getSharedPreferences("GameData", MODE_PRIVATE).edit().putBoolean(p, true).apply(); showUnlockDialog(p); refreshPetGrid() }?.start() } }
    private fun refreshPetGrid() { val g = findViewById<GridLayout>(R.id.gridPets) ?: return; g.removeAllViews(); val p = getSharedPreferences("GameData", MODE_PRIVATE); for (pet in petList) g.addView(ImageView(this).apply { setImageResource(resources.getIdentifier(pet, "drawable", packageName)); layoutParams = GridLayout.LayoutParams().apply { width = 250; height = 250; setMargins(10, 10, 10, 10) }; alpha = if (p.getBoolean(pet, false)) 1.0f else 0.1f }) }
    private fun showUnlockDialog(p: String) { AlertDialog.Builder(this).setTitle("ĐÃ MỞ KHÓA!").setView(ImageView(this).apply { setImageResource(resources.getIdentifier(p, "drawable", packageName)); setPadding(0, 50, 0, 0) }).setPositiveButton("OK", null).show() }
    private fun loadVocabularyFromJson() { try { allTopics = Gson().fromJson(assets.open("vocabulary.json").bufferedReader().use { it.readText() }, object : TypeToken<List<Topic>>() {}.type) } catch (e: Exception) {} }
    private fun loadCommonPhrasesFromJson() { try { commonPhrases = Gson().fromJson(assets.open("common_phrases.json").bufferedReader().use { it.readText() }, object : TypeToken<List<Topic>>() {}.type) } catch (e: Exception) {} }
    private fun setupTopicSpinner(s: Spinner?, tvH: TextView?, tvP: TextView?, img: ImageView?) { 
        if (allTopics.isEmpty() || s == null) return
        val starredWords = getStarredWords()
        val displayTopics = mutableListOf<Topic>()
        if (starredWords.isNotEmpty()) {
            displayTopics.add(Topic("Mục đã đánh dấu ⭐", starredWords))
        }
        displayTopics.addAll(allTopics)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayTopics.map { it.topic })
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { 
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) { 
                currentWordList = displayTopics[pos].words
                currentIndex = 0
                updateLearnUI(tvH, tvP, img) 
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {} 
        } 
    }
    private fun updateLearnUI(tvH: TextView?, tvP: TextView?, img: ImageView?) { if (currentWordList.isEmpty()) return; val w = currentWordList[currentIndex]; tvH?.text = w.hanzi; tvP?.text = w.pinyin; findViewById<TextView>(R.id.tvMeaning)?.text = w.meaning; val id = if (!w.imageName.isNullOrEmpty()) resources.getIdentifier(w.imageName, "drawable", packageName) else 0; img?.setImageResource(id); img?.visibility = if (id != 0) View.VISIBLE else View.GONE; drawingView?.setTargetText(w.hanzi); drawingView?.clear(); drawingView?.setShowGuideText(true); findViewById<ProgressBar>(R.id.learningProgress)?.apply { max = currentWordList.size; progress = currentIndex + 1 }; findViewById<TextView>(R.id.tvWordProgressLearn)?.text = "${currentIndex + 1} / ${currentWordList.size}"; val b = findViewById<ImageButton>(R.id.btnStar); b?.setImageResource(if (getSharedPreferences("MarkedWords", MODE_PRIVATE).getStringSet("stars", setOf())?.contains(w.hanzi) == true) R.drawable.btn_star_big_on else R.drawable.btn_star_big_off); b?.setOnClickListener { toggleStar(w, b) }; findViewById<TextView>(R.id.tvSpeakScore)?.visibility = View.GONE; findViewById<WebView>(R.id.webViewStroke)?.visibility = View.GONE; findViewById<WebView>(R.id.webViewStroke)?.scaleX = 1f; findViewById<WebView>(R.id.webViewStroke)?.scaleY = 1f }
    private fun showStrokeAnimation(webView: WebView?, hanzi: String) {
        val cleanHanzi = hanzi.filter { it.toString().matches("[\u4e00-\u9fa5]".toRegex()) }
        if (cleanHanzi.isEmpty()) {
            Toast.makeText(this, "Không có dữ liệu nét vẽ cho ký tự này", Toast.LENGTH_SHORT).show()
            webView?.visibility = View.GONE
            return
        }

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <script src="https://cdn.jsdelivr.net/npm/hanzi-writer@3.5/dist/hanzi-writer.min.js"></script>
                <style>
                    body { 
                        margin: 0; 
                        display: flex; 
                        flex-wrap: nowrap; 
                        justify-content: center; 
                        align-items: center; 
                        min-height: 100vh; 
                        background: transparent; 
                        overflow: hidden;
                    }
                    .char-container { 
                        flex: 0 0 auto;
                        background: transparent; 
                        border-radius: 15px; 
                        margin: 5px; 
                        transition: all 0.5s ease;
                    }
                    #target {
                        display: flex;
                        flex-direction: row;
                        flex-wrap: nowrap;
                        width: 100%;
                        justify-content: center;
                    }
                </style>
            </head>
            <body>
                <div id="target"></div>
                <script>
                    var hanziStr = '${cleanHanzi}';
                    var target = document.getElementById('target');
                    
                    async function drawChars() {
                        target.innerHTML = '';
                        var totalChars = hanziStr.length;
                        
                        var baseSize = 220; 
                        if (totalChars === 1) baseSize = 400; 
                        else if (totalChars === 2) baseSize = 300;
                        else if (totalChars === 3) baseSize = 250;

                        var containerWidth = window.innerWidth - 40; 
                        var size = Math.min(baseSize, (containerWidth / totalChars) - 10);
                        
                        target.style.justifyContent = 'center';

                        for (let i = 0; i < hanziStr.length; i++) {
                            let char = hanziStr[i];
                            var div = document.createElement('div');
                            div.className = 'char-container';
                            div.style.width = size + 'px';
                            div.style.height = size + 'px';
                            target.appendChild(div);

                            var writer = HanziWriter.create(div, char, {
                                width: size,
                                height: size,
                                padding: size * 0.1,
                                strokeAnimationSpeed: 1,
                                delayBetweenStrokes: 150,
                                strokeColor: '#7B1FA2',
                                outlineColor: '#EEEEEE',
                                showOutline: true
                            });
                            await writer.animateCharacter();
                            if (i < hanziStr.length - 1) {
                                await new Promise(r => setTimeout(r, 200));
                            }
                        }
                    }
                    drawChars();
                </script>
            </body>
            </html>
        """.trimIndent()
        webView?.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }
    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE }
    private fun speakChinese(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, null) }
    private fun playSound(id: Int) { try { MediaPlayer.create(this, id).apply { setOnCompletionListener { it.release() }; start() } } catch (e: Exception) {} }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); speechRecognizer?.destroy(); super.onDestroy() }
    private fun showConfetti() { val c = findViewById<FrameLayout>(R.id.viewConfetti) ?: return; c.visibility = View.VISIBLE; c.removeAllViews(); val r = java.util.Random(); for (i in 0..50) { val p = TextView(this).apply { text = listOf("⭐", "✨", "❤️", "💎", "🌸", "🎊")[r.nextInt(6)]; textSize = (10..25).random().toFloat() ; setTextColor(android.graphics.Color.parseColor(listOf("#FF0000", "#FFD700", "#00FF00", "#00BFFF", "#FF69B4", "#FFFFFF")[r.nextInt(6)])) }; c.addView(p); p.translationX = c.width / 2f; p.translationY = c.height / 2f; val a = r.nextDouble() * 2 * Math.PI; val v = (200..600).random(); p.animate().translationX(p.translationX + (Math.cos(a) * v).toFloat()).translationY(p.translationY + (Math.sin(a) * v).toFloat() + 400).rotation(720f).alpha(0f).setDuration((1000..3000).random().toLong()).withEndAction { c.removeView(p); if (c.childCount == 0) c.visibility = View.GONE }.start() } }
    private fun showFloatingText(a: View, t: String) { val tv = TextView(this).apply { this.text = t; textSize = 40f; setTextColor(android.graphics.Color.parseColor("#FFD700")); setTypeface(null, android.graphics.Typeface.BOLD) }; val r = findViewById<FrameLayout>(R.id.viewConfetti) ?: return; r.visibility = View.VISIBLE; r.addView(tv); tv.translationX = a.x + a.width / 4; tv.translationY = a.y; tv.animate().translationYBy(-300f).alpha(0f).setDuration(1000).withEndAction { r.removeView(tv) }.start() }
}
