package net.corda.chat

import co.paralleluniverse.fibers.Suspendable
import com.github.ricksbrown.cowsay.Cowsay
import javafx.application.Application
import javafx.beans.Observable
import javafx.event.ActionEvent
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.TransferMode
import javafx.stage.FileChooser
import javafx.stage.Stage
import net.corda.client.jfx.utils.observeOnFXThread
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.DataFeed
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.node.services.Vault
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.ProgressTracker
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import javafx.scene.control.ButtonType
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Alert
import java.util.jar.JarInputStream
import kotlin.streams.toList


class Chat : Contract {
    @CordaSerializable
    data class Message(val message: String, val to: AbstractParty, val from: AbstractParty,
                       override val participants: List<AbstractParty> = listOf(to, from),
                       val secureHash: SecureHash? = null) : ContractState

    object SendChatCommand : TypeOnlyCommandData()

    override fun verify(tx: LedgerTransaction) {
        val signers: List<PublicKey> = tx.commandsOfType<SendChatCommand>().single().signers
        val message: Message = tx.outputsOfType<Message>().single()
        requireThat {
            "the chat message is signed by the claimed sender" using (message.from.owningKey in signers)
        }
    }
}

@InitiatingFlow
@StartableByRPC
class SendChat(private val to: Party, private val message: String, private val secureHash: SecureHash?) : FlowLogic<Unit>() {
    override val progressTracker = ProgressTracker(object : ProgressTracker.Step("Sending") {})

    @Suspendable
    override fun call() {
        val stx: SignedTransaction = createMessageStx()
        progressTracker.nextStep()
        subFlow(FinalityFlow(stx))
    }

    private fun createMessageStx(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val txb = TransactionBuilder(notary)
        val me = ourIdentityAndCert.party
        txb.addOutputState(Chat.Message(message, to, me, secureHash = secureHash), Chat::class.qualifiedName!!)

        println("****")
        println("$this -->  ${this.secureHash}")
        println("****")

        if (secureHash != null) txb.addAttachment(secureHash)
        txb.addCommand(Chat.SendChatCommand, me.owningKey)
        return serviceHub.signInitialTransaction(txb)
    }
}

class ChatApp : Application() {
    private var rpc: CordaRPCOps? = null

    private val host: String
        get() = if (parameters.unnamed.size > 0) parameters.unnamed[0] else "localhost:10008"

    // Inside Graviton, this runs whilst the spinner animation is active, so we can be kinda slow here.
    override fun init() {
        try {
            rpc = CordaRPCClient(NetworkHostAndPort.parse(host)).start("user1", "test").proxy
        } catch (e: ActiveMQNotConnectedException) {
            // Delay showing the error message until we're inside start(), when JavaFX is started up fully.
        }
    }

    override fun start(stage: Stage) {
        val fxml = FXMLLoader(ChatApp::class.java.getResource("chat.fxml"))
        stage.scene = Scene(fxml.load())
        stage.scene.stylesheets.add("/net/corda/chat/chat.css")
        val controller: ChatUIController = fxml.getController()
        controller.stage = stage
        val rpc = rpc   // Allow smart cast
        if (rpc == null) {
            Alert(Alert.AlertType.ERROR, "Could not connect to server: $host").showAndWait()
            exitProcess(1)
        }
        controller.rpc = rpc
        val me: Party = rpc.nodeInfo().legalIdentities.single()
        controller.linkPartyList(me)
        controller.linkMessages()
        stage.title = me.name.organisation
        if ("Graviton" !in this.javaClass.classLoader.toString()) {
            stage.width = 940.0
            stage.height = 580.0
        }
        stage.show()

      // controller.onFileReceived(stage)

    }
}

@Suppress("UNUSED_PARAMETER")
class ChatUIController {
    class ClickableParty(val party: Party) {
        val isNotary: Boolean get() = "Notary" in party.name.organisation
        override fun toString() = party.name.organisation
    }

    class ClickableFile(val filename: String, val secureHash: SecureHash) {

    }

    lateinit var me: Party
    lateinit var textArea: TextArea
    lateinit var messageEdit: TextField
    lateinit var identitiesList: ListView<ClickableParty>
    lateinit var fileList: ListView<ClickableFile>
    lateinit var rpc: CordaRPCOps
    lateinit var usernameLabel: Label
    lateinit var stage: Stage


    fun sendMessage(event: ActionEvent) {
        send(messageEdit.text)
    }

    private fun send(message: String) {
        messageEdit.text = "Sending ..."
        messageEdit.isDisable = true
        try {
            rpc.startFlow(::SendChat, identitiesList.selectionModel.selectedItem.party, message, null)
        } finally {
            messageEdit.isDisable = false
            messageEdit.text = ""
        }
    }

    private fun sendFile(file: Path) {
        messageEdit.text = "Sending file..."
        messageEdit.isDisable = true
        try {
            val fo = File.createTempFile("/tmp", ".corda.zip")
            ZipOutputStream(fo.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry(file.fileName.toString()))
                Files.newInputStream(file).use {
                    it.copyTo(zos)
                }
            }
            val attachmentHash = rpc.uploadAttachment(fo.inputStream())
            fo.delete()

            textArea.text += "File $file uploaded as hash $attachmentHash\n"
            rpc.startFlow(::SendChat, identitiesList.selectionModel.selectedItem.party, file.fileName.toString(), attachmentHash)
        } finally {
            messageEdit.isDisable = false
            messageEdit.text = ""
        }
    }

    /*
    fun onFileReceived(stage: Stage): () -> Unit {
        return {
            val fc = FileChooser()
            fc.initialFileName = "yo.txt"
            val file = fc.showSaveDialog(stage)
            println("Writing to $file")
        }
    }
*/
    fun onMoo(event: ActionEvent) {
        val m = if (messageEdit.text.isBlank()) "moo" else messageEdit.text
        send(":cow:$m")
    }

    fun linkPartyList(me: Party) {
        this.me = me
        usernameLabel.text = "@" + me.name.organisation.toLowerCase()
        val (current, updates) = rpc.networkMapFeed()
        val names: List<ClickableParty> = current
                .flatMap { it.legalIdentities }
                .filterNot { it == me }
                .map { ClickableParty(it) }
                .filterNot { it.isNotary }
        identitiesList.items.addAll(names)
        identitiesList.selectionModel.selectedItems.addListener { _: Observable ->
            messageEdit.promptText = "Type message to ${identitiesList.selectionModel.selectedItems.first().party.name.organisation} here or drag and drop a file"
            messageEdit.setOnDragOver {
                if (it.gestureSource != messageEdit && it.dragboard.hasFiles()) {
                    it.acceptTransferModes(TransferMode.MOVE, TransferMode.COPY);
                }
                it.consume()
            }
            messageEdit.setOnDragDropped {
                val db = it.dragboard
                if (db.hasFiles()) {
                    db.files.forEach {
                        sendFile(Paths.get(it.path))
                    }
                }
            }
        }
        identitiesList.selectionModel.select(0)
        updates.observeOnFXThread().subscribe {
            if (it is NetworkMapCache.MapChange.Added)
                identitiesList.items.addAll(it.node.legalIdentities.map(::ClickableParty))
        }
    }

    fun linkMessages() {
        val messages = rpc.vaultTrack(Chat.Message::class.java)
        displayOldMessages(messages)
        messages.updates.observeOnFXThread().subscribe { it: Vault.Update<Chat.Message> ->
            if (it.containsType<Chat.Message>()) {
                val stateAndRef = it.produced.single()
                textArea.text += "[${Instant.now().formatted}] ${stateAndRef.from}: ${stateAndRef.msg}\n"
                if (stateAndRef.state.data.secureHash != null ) {
                    textArea.text += "Attachment: ${stateAndRef.state.data.secureHash}\n"
                }
                with(stateAndRef.state.data) {
                    if (secureHash != null && this.to == me)
                    {
                        fileTransferStateReceived(message, secureHash)
                    }
                }
                }
            }
    }

    private fun fileTransferStateReceived(message: String, secureHash: SecureHash) {
        val alert = Alert(AlertType.CONFIRMATION)
        alert.title = "File received"
        alert.headerText = "File $message received"
        alert.contentText = ""
        val buttonTypeDownload = ButtonType("Download")
        val buttonTypeCancel = ButtonType("Ignore", ButtonData.CANCEL_CLOSE)
        alert.buttonTypes.setAll(buttonTypeDownload, buttonTypeCancel)
       // val result = alert.showAndWait()
        class MockResult {
           open fun get() = buttonTypeDownload
       }

        val result = MockResult()

        if (result.get() === buttonTypeDownload)
        {
            rpc.attachmentExists(secureHash)
            val path = Paths.get(message)
            val attachment = rpc.openAttachment(secureHash)
            var file_name_data: Pair<String, ByteArray>? = null
            JarInputStream(attachment).use { jar ->
                while (true) {
                    val nje = jar.nextEntry ?: break
                    if (nje.isDirectory) {
                        continue
                    }
                    var destinationFilename = "${nje.name}"
                    if (System.getProperty("os.version") != "10.14") { // Don't trigger Mac os 14 Java FX bug.
                        with(FileChooser()) {
                            initialFileName = destinationFilename
                            var file = showSaveDialog(stage)
                            destinationFilename = file.name
                            file
                        }.writeBytes(jar.readBytes())
                    } else {
                        var inc = "incoming"
                        if (File(inc).isDirectory && File(inc).exists()) {
                            destinationFilename = "$inc/$destinationFilename"
                        } else {
                            Files.createDirectory(Paths.get(inc))
                        }
                        File("$inc/$destinationFilename").writeBytes(jar.readBytes())
                    }
                    textArea.text += "Written to $destinationFilename\n"
                }
            }
        }
    }

    private fun displayOldMessages(messages: DataFeed<Vault.Page<Chat.Message>, Vault.Update<Chat.Message>>) {
        // Get a list of timestamps and messages.
        val timestamps = messages.snapshot.statesMetadata.map { it.recordedTime }
        val fromAndMessages = messages.snapshot.states.map {
            Triple(it.from, it.msg, it.state.data.secureHash)
        }

        // Line them up and then sort them by time.
        val contents = (timestamps zip fromAndMessages).sortedBy { it.first }

        val sb = StringBuilder()
        for ((time, fromAndMessage) in contents) {
            sb.appendln("[${time.formatted}] ${fromAndMessage.first}: ${fromAndMessage.second}")
        }
        textArea.text = sb.toString()
    }

    private val Instant.formatted: String get() = this.atZone(ZoneId.systemDefault()).let { "${it.hour}:${it.minute}" }
    private val StateAndRef<Chat.Message>.from get() = state.data.from.nameOrNull()!!.organisation
    private val StateAndRef<Chat.Message>.msg get() = if (state.data.message.startsWith(":cow:")) System.lineSeparator() + Cowsay.say(arrayOf(state.data.message.drop(5))) else state.data.message
}

fun main(args: Array<String>) {
    Application.launch(ChatApp::class.java, *args)
}