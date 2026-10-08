package com.example.lorascanner // Mantenha o nome do seu pacote aqui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.Executors

// Estrutura de dados para armazenar cada módulo descoberto na rede
data class ModuloDescoberto(
    val id: String,
    val lat: String,
    val lon: String,
    val rssi: String,
    val snr: String,
    val hora: String,
    val ultimoTipo: String
)

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var usbPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val executorService = Executors.newSingleThreadExecutor()

    // Acumulador de linha serial para evitar fragmentação de bytes
    private val bufferEntrada = StringBuilder()

    // Mapeamento dos módulos ativos encontrados (Chave = ID do Módulo)
    private val modulosEncontrados = HashMap<String, ModuloDescoberto>()

    // Elementos da Interface
    private lateinit var tvStatus: TextView
    private lateinit var tvGpsInfo: TextView
    private lateinit var btnModoRadio: Button
    private lateinit var btnMonitoramento: Button
    private lateinit var spEnderecos: Spinner
    private lateinit var viewLedSos: View
    private lateinit var tvAlertaSos: TextView

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val LOCATION_PERMISSION_REQ_CODE = 1001

    private var ultimaLat: Double = 0.0
    private var ultimaLon: Double = 0.0

    // Configurações da Rede
    private var enderecoSelecionado: String = "3"
    private var modoMaximoAlcance: Boolean = false
    private var modoMonitoramentoAtivo: Boolean = false
    private val logFileName = "telemetria_lora_log.csv"

    // Feed de eventos sequenciais
    private val historicoPings = LinkedList<String>()
    private val MAX_HISTORICO = 30

    // Ciclo SOS (5s)
    private val sosHandler = Handler(Looper.getMainLooper())
    private var sosAtivo: Boolean = false
    private val sosRunnable = object : Runnable {
        override fun run() {
            if (sosAtivo) {
                enviarMensagemSos()
                sosHandler.postDelayed(this, 5000)
            }
        }
    }

    // Ciclo de Monitoramento com Jitter (4.5s a 6.5s)
    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (modoMonitoramentoAtivo && usbPort != null) {
                localizarModulos()
                val tempoJitter = (4500..6500).random().toLong()
                pingHandler.postDelayed(this, tempoJitter)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvGpsInfo = findViewById(R.id.tvGpsInfo)
        btnModoRadio = findViewById(R.id.btnModoRadio)
        btnMonitoramento = findViewById(R.id.btnMonitoramento)
        spEnderecos = findViewById(R.id.spEnderecos)
        viewLedSos = findViewById(R.id.viewLedSos)
        tvAlertaSos = findViewById(R.id.tvAlertaSos)

        val btnSos = findViewById<Button>(R.id.btnSos)
        val btnConectar = findViewById<Button>(R.id.btnConectar)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnExportarLog = findViewById<Button>(R.id.btnExportarLog)
        val btnLimparLog = findViewById<Button>(R.id.btnLimparLog)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        configurarSpinnerEnderecos()
        obterLocalizacaoGps()
        inicializarArquivoLog()

        btnConectar.setOnClickListener { conectarUSB() }

        btnScan.setOnClickListener {
            if (usbPort == null) {
                tvStatus.text = "Status: Conecte o USB primeiro!"
                tvStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }
            modulosEncontrados.clear()
            localizarModulos()
            atualizarDisplay("🔍 Escaneando rede LoRa... Aguardando respostas PONG...")
        }

        btnModoRadio.setOnClickListener {
            modoMaximoAlcance = !modoMaximoAlcance
            atualizarModoRadioUI()
            aplicarParametrosRadio()
        }

        btnMonitoramento.setOnClickListener {
            modoMonitoramentoAtivo = !modoMonitoramentoAtivo
            atualizarMonitoramentoUI()
        }

        btnExportarLog.setOnClickListener { exportarArquivoLog() }
        btnLimparLog.setOnClickListener { limparArquivoLog() }

        btnSos.setOnClickListener {
            if (usbPort == null) {
                tvStatus.text = "Status: Conecte o USB primeiro!"
                tvStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }
            sosAtivo = !sosAtivo
            if (sosAtivo) {
                btnSos.text = "🚨 PARAR SOS"
                btnSos.setBackgroundColor(Color.parseColor("#B71C1C"))
                sosHandler.post(sosRunnable)
            } else {
                btnSos.text = "🚨 ENVIAR SOS (BROADCAST)"
                btnSos.setBackgroundColor(Color.parseColor("#D32F2F"))
                sosHandler.removeCallbacks(sosRunnable)
            }
        }
    }

    private fun configurarSpinnerEnderecos() {
        val opcoes = arrayOf("ID 1", "ID 2", "ID 3", "ID 4", "ID 5")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opcoes)
        spEnderecos.adapter = adapter
        spEnderecos.setSelection(2) // ID 3 por padrão

        spEnderecos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val novoEndereco = (position + 1).toString()
                if (novoEndereco != enderecoSelecionado) {
                    enderecoSelecionado = novoEndereco
                    aplicarNovoEnderecoHardware()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun enviarComandoAt(comando: String) {
        if (usbPort == null) return
        try {
            val cmdComQuebra = if (comando.endsWith("\r\n")) comando else "$comando\r\n"
            usbPort?.write(cmdComQuebra.toByteArray(), 1000)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun aplicarNovoEnderecoHardware() {
        if (usbPort != null) {
            enviarComandoAt("AT+ADDRESS=$enderecoSelecionado")
            tvStatus.text = "Status: Endereço do rádio alterado para ID $enderecoSelecionado!"
            tvStatus.setTextColor(Color.GREEN)
        }
    }

    private fun aplicarParametrosRadio() {
        if (modoMaximoAlcance) {
            enviarComandoAt("AT+PARAMETER=11,7,4,12")
            Thread.sleep(100)
            enviarComandoAt("AT+CRFOP=22")
        } else {
            enviarComandoAt("AT+PARAMETER=9,7,1,12")
            Thread.sleep(100)
            enviarComandoAt("AT+CRFOP=15")
        }
    }

    private fun obterLocalizacaoGps() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQ_CODE
            )
            return
        }

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    ultimaLat = location.latitude
                    ultimaLon = location.longitude
                    atualizarNomeCidadeEGeoreferencia(location.latitude, location.longitude)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun atualizarNomeCidadeEGeoreferencia(lat: Double, lon: Double) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val nomeCidade = addresses[0].locality ?: addresses[0].subAdminArea ?: "Cidade N/A"
                        runOnUiThread {
                            tvGpsInfo.text = "📍 $nomeCidade\nLat: %.5f | Lon: %.5f".format(lat, lon)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                var nomeCidade = "Cidade N/A"
                if (!addresses.isNullOrEmpty()) {
                    nomeCidade = addresses[0].locality ?: addresses[0].subAdminArea ?: nomeCidade
                }
                tvGpsInfo.text = "📍 $nomeCidade\nLat: %.5f | Lon: %.5f".format(lat, lon)
            }
        } catch (e: Exception) {
            tvGpsInfo.text = "📍 GPS: Lat: %.5f | Lon: %.5f".format(lat, lon)
        }
    }

    private fun inicializarArquivoLog() {
        val file = File(filesDir, logFileName)
        val cabecalhoCorreto = "Timestamp;ID_Origem;Tipo_Msg;RSSI;SNR;Hops;Lat_Emissor;Lon_Emissor;Lat_Receptor;Lon_Receptor\n"

        if (!file.exists()) {
            try {
                val fos = openFileOutput(logFileName, Context.MODE_PRIVATE)
                fos.write(cabecalhoCorreto.toByteArray())
                fos.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                val primeiraLinha = file.useLines { it.firstOrNull() }
                if (primeiraLinha == null || !primeiraLinha.contains("Lat_Emissor")) {
                    val fos = openFileOutput(logFileName, Context.MODE_PRIVATE)
                    fos.write(cabecalhoCorreto.toByteArray())
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun salvarRegistroLog(
        idOrigem: String,
        tipoMsg: String,
        rssi: String,
        snr: String,
        hops: String,
        latEmissor: String,
        lonEmissor: String
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val linhaCsv = String.format(
            Locale.US,
            "%s;%s;%s;%s;%s;%s;%s;%s;%.5f;%.5f\n",
            timestamp, idOrigem, tipoMsg, rssi, snr, hops, latEmissor, lonEmissor, ultimaLat, ultimaLon
        )

        try {
            val fos = openFileOutput(logFileName, Context.MODE_APPEND)
            fos.write(linhaCsv.toByteArray())
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun limparArquivoLog() {
        val cabecalhoCorreto = "Timestamp;ID_Origem;Tipo_Msg;RSSI;SNR;Hops;Lat_Emissor;Lon_Emissor;Lat_Receptor;Lon_Receptor\n"
        try {
            val fos = openFileOutput(logFileName, Context.MODE_PRIVATE)
            fos.write(cabecalhoCorreto.toByteArray())
            fos.close()
            tvStatus.text = "Status: Log resetado (Cabeçalho de 10 colunas pronto)."
            tvStatus.setTextColor(Color.GREEN)
        } catch (e: Exception) {
            tvStatus.text = "Erro ao limpar log: ${e.message}"
            tvStatus.setTextColor(Color.RED)
        }
    }

    private fun exportarArquivoLog() {
        val file = File(filesDir, logFileName)
        if (!file.exists() || file.length() <= 100) {
            tvStatus.text = "Nenhum dado de telemetria registrado ainda."
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar Log de Telemetria LoRa"))
    }

    private fun atualizarMonitoramentoUI() {
        if (modoMonitoramentoAtivo) {
            btnMonitoramento.text = "📊 MONITORAR: ON"
            btnMonitoramento.setBackgroundColor(Color.parseColor("#00897B"))
            pingHandler.post(pingRunnable)
        } else {
            btnMonitoramento.text = "📊 MONITORAR: OFF"
            btnMonitoramento.setBackgroundColor(Color.parseColor("#616161"))
            pingHandler.removeCallbacks(pingRunnable)
        }
    }

    private fun atualizarModoRadioUI() {
        if (modoMaximoAlcance) {
            btnModoRadio.text = "🔥 Máximo"
            btnModoRadio.setBackgroundColor(Color.parseColor("#E65100"))
        } else {
            btnModoRadio.text = "🍃 Eco"
            btnModoRadio.setBackgroundColor(Color.parseColor("#388E3C"))
        }
    }

    private fun conectarUSB() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            tvStatus.text = "Status: CP2102 não encontrado"
            tvStatus.setTextColor(Color.RED)
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent("com.android.example.USB_PERMISSION").setPackage(packageName), flags
            )

            manager.requestPermission(device, permissionIntent)
            tvStatus.text = "Status: Aguardando permissão USB..."
            tvStatus.setTextColor(Color.YELLOW)
            return
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            tvStatus.text = "Status: Falha ao abrir conexão USB"
            tvStatus.setTextColor(Color.RED)
            return
        }

        usbPort = driver.ports[0]
        try {
            usbPort?.open(connection)
            usbPort?.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            try {
                usbPort?.setDTR(true)
                usbPort?.setRTS(true)
            } catch (e: Exception) { }

            Thread.sleep(100)

            enviarComandoAt("AT")
            Thread.sleep(100)
            enviarComandoAt("AT+NETWORKID=10")
            Thread.sleep(100)
            enviarComandoAt("AT+ADDRESS=$enderecoSelecionado")
            Thread.sleep(100)
            aplicarParametrosRadio()

            tvStatus.text = "Status: Conectado ao CP2102 (ID: $enderecoSelecionado)!"
            tvStatus.setTextColor(Color.GREEN)

            iniciarLeitura()

        } catch (e: IOException) {
            tvStatus.text = "Erro ao abrir porta: ${e.message}"
            tvStatus.setTextColor(Color.RED)
        }
    }

    private fun iniciarLeitura() {
        usbIoManager?.stop()
        usbIoManager = SerialInputOutputManager(usbPort, this)
        executorService.submit(usbIoManager)
    }

    private fun localizarModulos() {
        val payload = "PING;%.5f;%.5f;0".format(Locale.US, ultimaLat, ultimaLon)
        enviarComandoAt("AT+SEND=0,${payload.length},$payload")
    }

    private fun enviarMensagemSos() {
        val payload = "SOS;%.5f;%.5f;0".format(Locale.US, ultimaLat, ultimaLon)
        enviarComandoAt("AT+SEND=0,${payload.length},$payload")
    }

    // === RECEPÇÃO SERIAL COM BUFFER DE LINHA COMPLETA ===
    override fun onNewData(data: ByteArray) {
        val chunk = String(data, Charsets.UTF_8)

        synchronized(bufferEntrada) {
            bufferEntrada.append(chunk)

            while (bufferEntrada.contains("\n")) {
                val indexLinha = bufferEntrada.indexOf("\n")
                val linhaCompleta = bufferEntrada.substring(0, indexLinha).trim()
                bufferEntrada.delete(0, indexLinha + 1)

                if (linhaCompleta.isNotEmpty()) {
                    runOnUiThread {
                        processarLinhaCompleta(linhaCompleta)
                    }
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        runOnUiThread {
            tvStatus.text = "Erro de leitura USB: ${e.message}"
        }
    }

    private fun processarLinhaCompleta(linha: String) {
        if (linha.startsWith("+OK") || linha.startsWith("+ERR")) {
            return
        }

        if (linha.startsWith("+RCV=")) {
            try {
                val dadosLimpos = linha.replace("+RCV=", "")
                val partes = dadosLimpos.split(",")

                if (partes.size >= 5) {
                    val remetente = partes[0].trim()
                    val conteudo = partes[2].trim()
                    val rssi = partes[3].trim()
                    val snr = partes[4].trim()

                    val subPartes = conteudo.split(";")
                    var statusMsg = conteudo
                    var latRemota = "N/A"
                    var lonRemota = "N/A"
                    var hops = "0"

                    if (subPartes.size >= 4) {
                        statusMsg = subPartes[0]
                        latRemota = subPartes[1]
                        lonRemota = subPartes[2]
                        hops = subPartes[3]
                    } else if (subPartes.size >= 3) {
                        statusMsg = subPartes[0]
                        latRemota = subPartes[1]
                        lonRemota = subPartes[2]
                    }

                    val horaExata = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                    // Registra / Atualiza o nó na lista de Módulos Descobertos
                    modulosEncontrados[remetente] = ModuloDescoberto(
                        id = remetente,
                        lat = latRemota,
                        lon = lonRemota,
                        rssi = rssi,
                        snr = snr,
                        hora = horaExata,
                        ultimoTipo = statusMsg
                    )

                    // 1. Grava no Log CSV APENAS se o modo Monitoramento estiver ligado
                    if (modoMonitoramentoAtivo) {
                        salvarRegistroLog(remetente, statusMsg, rssi, snr, hops, latRemota, lonRemota)
                    }

                    // 2. Responde PONG INCONDICIONALMENTE a qualquer PING recebido na rede
                    // com um Atraso Aleatório (Jitter de 200ms a 1200ms) para evitar colisão de RF
                    if (statusMsg == "PING") {
                        val hopsInt = hops.toIntOrNull() ?: 0
                        val atrasoResposta = (200..1200).random().toLong()
                        Handler(Looper.getMainLooper()).postDelayed({
                            responderPingMonitoramento(remetente, hopsInt)
                        }, atrasoResposta)
                    }

                    // 3. Alerta SOS
                    if (statusMsg.contains("SOS")) {
                        viewLedSos.setBackgroundColor(Color.RED)
                        tvAlertaSos.text = "🚨 ALERTA SOS - Nó ID: $remetente\nPos: ($latRemota, $lonRemota)"
                        tvAlertaSos.setTextColor(Color.RED)
                    }

                    // 4. Adiciona ao Feed de eventos
                    val registroFeed = "[$horaExata] ID: $remetente | $statusMsg\n📍 Pos: ($latRemota, $lonRemota)\n📡 Sinal: $rssi dBm | SNR: $snr | Hops: $hops"
                    historicoPings.addFirst(registroFeed)
                    if (historicoPings.size > MAX_HISTORICO) {
                        historicoPings.removeLast()
                    }

                    atualizarDisplay()
                }
            } catch (e: Exception) {
                // Descarta pacotes malformados
            }
        }
    }

    private fun responderPingMonitoramento(destinoId: String, hopsRecebidos: Int) {
        val payload = "PONG;%.5f;%.5f;$hopsRecebidos".format(Locale.US, ultimaLat, ultimaLon)
        enviarComandoAt("AT+SEND=$destinoId,${payload.length},$payload")
    }

    private fun atualizarDisplay(mensagemInfo: String? = null) {
        val builder = StringBuilder()

        // 1. QUADRO SUPERIOR: MÓDULOS ENCONTRADOS / MAPEADOS
        builder.append("=📡 MÓDULOS ENCONTRADOS (${modulosEncontrados.size}) =\n")

        if (modulosEncontrados.isEmpty()) {
            builder.append(mensagemInfo ?: "Nenhum módulo respondeu ainda.\nToque em '🔍 LOCALIZAR MÓDULOS' para escanear.")
            builder.append("\n")
        } else {
            for ((id, mod) in modulosEncontrados) {
                builder.append("📱 Módulo ID [$id] (${mod.ultimoTipo})\n")
                builder.append("   📍 Pos: (${mod.lat}, ${mod.lon})\n")
                builder.append("   📶 Sinal: ${mod.rssi} dBm | SNR: ${mod.snr}\n")
                builder.append("   🕒 Visto às: ${mod.hora}\n")
            }
        }

        builder.append("----------------------------------------\n")

        // 2. QUADRO INFERIOR: HISTÓRICO DE PACOTES EM TEMPO REAL
        builder.append("= HISTÓRICO DE EVENTOS RECENTES =\n")

        if (historicoPings.isEmpty()) {
            builder.append("Aguardando tráfego de dados...")
        } else {
            for (registro in historicoPings) {
                builder.append("• $registro\n---\n")
            }
        }

        tvStatus.text = builder.toString()
        tvStatus.setTextColor(Color.WHITE)
    }

    override fun onDestroy() {
        super.onDestroy()
        sosHandler.removeCallbacks(sosRunnable)
        pingHandler.removeCallbacks(pingRunnable)

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            usbIoManager?.stop()
            usbPort?.close()
        } catch (e: Exception) {
            // Liberar recursos
        }
    }
}