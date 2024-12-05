package org.maproulette.utils

object CSVEncoder {
  def encodeRow(row: List[Any]): String = {
    row.map(escape).mkString(",")
  }

  private def escape(value: Any): String = {
    val strValue = value.toString
    if (strValue.contains(",") || strValue.contains("\"") || strValue.contains("\n")) {
      "\"" + strValue.replace("\"", "\"\"") + "\""
    } else {
      strValue
    }
  }
} 