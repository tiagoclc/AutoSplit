package com.fofinhos.autosplit

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gerencia a execução de comandos via shell do ADB e a conexão local.
 */
class AdbShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AutoSplitADB"
        private const val PORTA_ADB = 5555
        private const val ARQUIVO_CHAVE_PRIVADA = "adbkey"
        private const val ARQUIVO_CHAVE_PUBLICA = "adbkey.pub"
        
        @Volatile
        private var parDeChavesCache: AdbKeyPair? = null
        private val travaChaves = Any()
        
        @Volatile
        private var dadbCompartilhado: Dadb? = null
        private val travaConexao = Any()
        
        // Controle de autenticação
        private val autenticacaoPendente = AtomicBoolean(false)
        private val pollingIniciado = AtomicBoolean(false)
        
        private val pollingExecutor = Executors.newSingleThreadExecutor()
        
        fun estaPendente(): Boolean = autenticacaoPendente.get()
    }
    
    data class ResultadoShell(
        val codigoSaida: Int,
        val saida: String
    )
    
    fun executarSync(comando: String): ResultadoShell {
        var ultimaExcecao: Exception? = null
        
        repeat(2) { tentativa ->
            try {
                val dadb = obterOuCriarConexao()
                val resultado = dadb.shell(comando)
                return ResultadoShell(resultado.exitCode, resultado.allOutput)
            } catch (e: Exception) {
                ultimaExcecao = e
                Log.w(TAG, "Falha na execução do comando (tentativa ${tentativa + 1}/2): ${e.message}")
                
                // Em caso de erro, limpa a conexão para forçar uma nova na próxima tentativa
                synchronized(travaConexao) {
                    try { dadbCompartilhado?.close() } catch (ignored: Exception) {}
                    dadbCompartilhado = null
                }
                
                if (tentativa == 0) {
                    Thread.sleep(500) // Pequena pausa antes de tentar novamente
                }
            }
        }
        throw ultimaExcecao ?: Exception("Falha na execução do comando ADB")
    }

    fun obterOuCriarConexao(): Dadb {
        synchronized(travaConexao) {
            var dadb = dadbCompartilhado
            if (dadb != null) {
                try {
                    // Teste rápido para verificar se a conexão ainda é válida
                    val teste = dadb.shell("echo ok")
                    if (teste.exitCode == 0) {
                        autenticacaoPendente.set(false)
                        return dadb
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Conexão cacheada não está mais funcional, tentando reconectar...")
                }
                
                // Fecha a conexão antiga se estiver ruim
                try { dadb.close() } catch (ignored: Exception) {}
                dadbCompartilhado = null
            }
            
            if (!portaAdbAberta()) {
                throw Exception("A porta ADB ($PORTA_ADB) não está aberta. Ative a Depuração por Wi-Fi.")
            }
            
            val parDeChaves = obterOuCriarParDeChaves()
            
            // Inicia o monitoramento de autenticação se ainda não estiver ativo
            if (!autenticacaoPendente.get() && pollingIniciado.compareAndSet(false, true)) {
                autenticacaoPendente.set(true)
                iniciarMonitoramentoAutenticacao(parDeChaves)
            }
            
            // Tenta uma conexão imediata com timeout de 5 segundos
            dadb = tentarConectarComTimeout(parDeChaves, 5000)
            
            if (dadb != null) {
                dadbCompartilhado = dadb
                autenticacaoPendente.set(false)
                pollingIniciado.set(false)
                return dadb
            } else {
                throw Exception("Aguardando autorização ADB. Por favor, aceite o prompt no dispositivo.")
            }
        }
    }
    
    private fun portaAdbAberta(): Boolean {
        return try {
            Socket("127.0.0.1", PORTA_ADB).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun tentarConectarComTimeout(parDeChaves: AdbKeyPair, timeoutMs: Int): Dadb? {
        return try {
            // Cria a instância Dadb com os timeouts configurados
            val novoDadb = Dadb.create(
                host = "127.0.0.1",
                port = PORTA_ADB,
                keyPair = parDeChaves,
                connectTimeout = timeoutMs,
                socketTimeout = timeoutMs
            )
            
            // O teste de shell forçará o handshake e falhará se não estiver autorizado
            val teste = novoDadb.shell("echo ok")
            if (teste.exitCode == 0) {
                novoDadb
            } else {
                try { novoDadb.close() } catch (e: Exception) {}
                null
            }
        } catch (e: Exception) {
            // Timeout ou erro de conexão são esperados se não estiver autorizado ainda
            null
        }
    }
    
    private fun iniciarMonitoramentoAutenticacao(parDeChaves: AdbKeyPair) {
        pollingExecutor.execute {
            try {
                var tentativas = 0
                while (autenticacaoPendente.get() && tentativas < 60) {
                    tentativas++
                    try {
                        Thread.sleep(3000)
                    } catch (e: InterruptedException) {
                        break
                    }
                    
                    if (!portaAdbAberta()) continue
                    
                    try {
                        val testeDadb = tentarConectarComTimeout(parDeChaves, 5000)
                        if (testeDadb != null) {
                            synchronized(travaConexao) {
                                try { dadbCompartilhado?.close() } catch (ignored: Exception) {}
                                dadbCompartilhado = testeDadb
                            }
                            autenticacaoPendente.set(false)
                            pollingIniciado.set(false)
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Aguardando autorização no polling...")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro crítico no executor de polling", e)
            } finally {
                autenticacaoPendente.set(false)
                pollingIniciado.set(false)
            }
        }
    }
    
    private fun obterOuCriarParDeChaves(): AdbKeyPair {
        parDeChavesCache?.let { return it }
        
        synchronized(travaChaves) {
            parDeChavesCache?.let { return it }
            
            val pastaArquivos = context.filesDir
            val arquivoPrivado = File(pastaArquivos, ARQUIVO_CHAVE_PRIVADA)
            val arquivoPublico = File(pastaArquivos, ARQUIVO_CHAVE_PUBLICA)
            
            val par = if (arquivoPrivado.exists() && arquivoPublico.exists()) {
                try {
                    Log.d(TAG, "Lendo chaves ADB existentes")
                    AdbKeyPair.read(arquivoPrivado, arquivoPublico)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao ler chaves, gerando novas", e)
                    gerarESalvarChaves(arquivoPrivado, arquivoPublico)
                }
            } else {
                Log.d(TAG, "Chaves ADB não encontradas, gerando novas")
                gerarESalvarChaves(arquivoPrivado, arquivoPublico)
            }
            
            parDeChavesCache = par
            return par
        }
    }
    
    private fun gerarESalvarChaves(arquivoPrivado: File, arquivoPublico: File): AdbKeyPair {
        Log.d(TAG, "Gerando novas chaves ADB em: ${arquivoPrivado.absolutePath}")
        AdbKeyPair.generate(arquivoPrivado, arquivoPublico)
        return AdbKeyPair.read(arquivoPrivado, arquivoPublico)
    }
}
