/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.utils

import java.security.MessageDigest
import java.util.Arrays

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.{Inject, Singleton}
import org.apache.commons.codec.binary.Base64
import org.maproulette.Config

/**
  * @author mcuthbert
  */
@Singleton
class Crypto @Inject() (config: Config) {
  val key: String = config.config.get[String]("maproulette.secret.key")

  def encrypt(value: String): String = {
    val cipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, keyToSpec)
    Base64.encodeBase64String(cipher.doFinal(value.getBytes("UTF-8")))
  }

  def keyToSpec: SecretKeySpec = {
    var keyBytes: Array[Byte] = (Crypto.SALT + key).getBytes("UTF-8")
    val sha: MessageDigest    = MessageDigest.getInstance("SHA-1")
    keyBytes = sha.digest(keyBytes)
    keyBytes = Arrays.copyOf(keyBytes, Crypto.BYTE_LENGTH)
    new SecretKeySpec(keyBytes, "AES")
  }

  def decrypt(encryptedValue: String): String = {
    val cipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5PADDING")
    cipher.init(Cipher.DECRYPT_MODE, keyToSpec)
    new String(cipher.doFinal(Base64.decodeBase64(encryptedValue)))
  }
}

object Crypto {
  private val SALT: String = "jMhKlOuJnM34G6NHkqo9V010GhLAqOpF0BePojHgh1HgNg8^72k"
  private val BYTE_LENGTH  = 16
}
