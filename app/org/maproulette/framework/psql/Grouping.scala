/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import anorm.NamedParameter
import org.maproulette.framework.psql.filter.SQLClause

/**
  * @author mcuthbert
  */
case class GroupField(name: String, table: Option[String] = None)

case class Grouping(groups: GroupField*) extends SQLClause {
  override def sql()(implicit table: String = ""): String = {
    if (groups.isEmpty) {
      ""
    } else {
      val groupString = groups
        .map(group => {
          SQLUtils.testColumnName(group.name)
          if (group.name.contains(".")) {
            group.name
          } else {
            group.table.getOrElse(table) match {
              case ""    => group.name
              case value => s"$value.${group.name}"
            }
          }
        })
        .mkString(",")
      s"GROUP BY $groupString"
    }
  }

  override def parameters(): List[NamedParameter] = List.empty
}

object Grouping {
  def >(groups: String*): Grouping = Grouping(groups.map(GroupField(_)): _*)
}
