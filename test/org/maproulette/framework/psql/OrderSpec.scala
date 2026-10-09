/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import org.scalatestplus.play.PlaySpec

/**
  * @author mcuthbert
  */
class OrderSpec extends PlaySpec {
  private val FIELD  = "field"
  private val FIELD2 = "field2"

  "Order" should {
    "Not set ordering if on order fields provided" in {
      Order().sql() mustEqual ""
    }

    "Set ordering if at least one order field is provided in ascending order" in {
      val order = Order > (FIELD, Order.ASC)
      order.sql() mustEqual s"ORDER BY $FIELD ASC"
    }

    "Set ordering if at least one order field provided in descending order" in {
      val order = Order > (FIELD, Order.DESC)
      order.sql() mustEqual s"ORDER BY $FIELD DESC"
    }

    "set ordering with differing directions" in {
      val order = Order(List(OrderField(FIELD), OrderField(FIELD2, Order.ASC)))
      order.sql() mustEqual s"ORDER BY $FIELD DESC,$FIELD2 ASC"
    }

    "Set ordering for multiple order fields using ASC" in {
      val order = Order(List(OrderField(FIELD, Order.ASC), OrderField(FIELD2, Order.ASC)))
      order.sql() mustEqual s"ORDER BY $FIELD,$FIELD2 ASC"
    }

    "Set ordering for multiple order fields using DESC" in {
      Order(List(OrderField(FIELD), OrderField(FIELD2)))
        .sql() mustEqual s"ORDER BY $FIELD,$FIELD2 DESC"
    }

    "Reject an order field that would start a SQL comment" in {
      intercept[java.sql.SQLException] {
        Order(List(OrderField("id--"))).sql()
      }
    }

    "Reject an order field containing a hyphen" in {
      intercept[java.sql.SQLException] {
        Order(List(OrderField("review-requested-by"))).sql()
      }
    }
  }
}
