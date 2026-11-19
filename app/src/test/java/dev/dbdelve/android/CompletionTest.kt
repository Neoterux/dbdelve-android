package dev.dbdelve.android

import dev.dbdelve.android.Completion.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.dbdelve_core.Engine

class CompletionTest {
    private val tables = listOf("orders", "order_items", "customers", "Mixed Case")
    private val columns = mapOf(
        "orders" to listOf("id", "status", "total", "customer_id"),
        "customers" to listOf("id", "name", "country"),
        "order_items" to listOf("order_id", "qty"),
    )

    private fun names(engine: Engine, text: String, cursor: Int = text.length) =
        Completion.suggest(engine, text, Completion.word(text, cursor)!!, tables, columns::get).map { it.insert }

    @Test
    fun the_word_at_the_cursor() {
        assertEquals(Completion.Word(null, "sta", 7), Completion.word("SELECT sta", 10))
        assertEquals(Completion.Word("o", "", 9), Completion.word("SELECT o.", 9))
        assertEquals(Completion.Word("o", "st", 9), Completion.word("SELECT o.st FROM orders o", 11))
        assertNull("mid-word", Completion.word("SELECT status", 9))
        assertNull("after a space", Completion.word("SELECT ", 7))
        assertNull("a number", Completion.word("LIMIT 10", 8))
    }

    @Test
    fun columns_of_the_tables_named_then_tables() {
        assertEquals(listOf("status"), names(Engine.POSTGRES, "SELECT * FROM orders WHERE st"))
        assertEquals(listOf("order_items", "orders"), names(Engine.POSTGRES, "SELECT * FROM ord").sorted())
        // `c`: columns of the tables named, then any table.
        assertEquals(listOf("customer_id", "country", "customers"), names(Engine.POSTGRES, "SELECT c FROM orders, customers WHERE c"))
    }

    @Test
    fun after_an_alias_its_table_columns() {
        val text = "SELECT o. FROM orders AS o JOIN customers c ON c.id = o.customer_id"
        assertEquals(listOf("id", "status", "total", "customer_id"), names(Engine.POSTGRES, text, 9))
        assertEquals(listOf("country"), names(Engine.MY_SQL, "SELECT c.co FROM customers c", 11))
    }

    @Test
    fun names_are_quoted_as_the_engine_reads_them() {
        assertEquals("\"Mixed Case\"", Completion.quote(Engine.POSTGRES, "Mixed Case", table = true))
        assertEquals("\"Orders\"", Completion.quote(Engine.POSTGRES, "Orders", table = true))
        assertEquals("Orders", Completion.quote(Engine.MY_SQL, "Orders", table = true))
        assertEquals("`order`", Completion.quote(Engine.MY_SQL, "order", table = false))
        assertEquals(listOf("\"Mixed Case\""), names(Engine.POSTGRES, "SELECT * FROM Mi"))
    }

    @Test
    fun mongo_collections_after_db_and_fields_of_the_one_named() {
        assertEquals(listOf("customers"), names(Engine.MONGO, "db.cu"))
        assertEquals(listOf("status"), names(Engine.MONGO, "db.orders.find({st"))
    }
}
