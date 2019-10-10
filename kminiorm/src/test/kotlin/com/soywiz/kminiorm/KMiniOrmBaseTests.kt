package com.soywiz.kminiorm

import java.util.*
import kotlin.test.*

abstract class KMiniOrmBaseTests(val db: Db) {
    @Test
    fun testBasicTable() = suspendTest {
        val table = db.table<MyTestTable>()
        table.delete { everything }
        val item0 = table.insert(MyTestTable(
                _id = DbKey("5d9c47664b3f792e8d8ceb33"),
                uuid = UUID.fromString("5190ac73-e244-48fb-a535-36a3b3e653db"),
                date = Date(1570522248800L),
                long = 1234567890123L,
                int = 12345678,
                string = "My String",
                listString = listOf("hello", "world"),
                mapString = mapOf("hello" to "world")
        ))
        val items = table.findAll().toList()
        assertEquals(1, items.size)
        val item1 = items.first()
        //println("item0: $item0")
        //println("item1: $item1")
        assertEquals(item0._id, item1._id)
        assertEquals(item0.uuid, item1.uuid)
        assertEquals(item0.date, item1.date)
        assertEquals(item0.long, item1.long)
        assertEquals(item0.int, item1.int)
        assertEquals(item0.string, item1.string)
        assertEquals(item0.listString, item1.listString)
        assertEquals(item0.mapString, item1.mapString)
        assertEquals(item0, item1)

        assertEquals(1L, table.update(set = Partial(MyTestTable::int to 3, MyTestTable::string to "hello")) { id(item0._id) })
        assertEquals(3, table.findById(item0._id)?.int)
        assertEquals("hello", table.findById(item0._id)?.string)

        table.delete { everything }
        assertEquals(0, table.findAll().count())
    }

    @Test
    fun testBasicCounter() = suspendTest {
        val table = db.table<MyCounterTable>()
        table.delete { everything }
        val item = table.insert(MyCounterTable())
        assertEquals(
                1L,
                table.update(
                    increment = Partial(
                            MyCounterTable::counter1 to +10,
                            MyCounterTable::counter2 to -10
                    )
                ) { MyCounterTable::_id eq item._id }
        )
        val item2 = table.findById(item._id)
        assertEquals(1010, item2?.counter1)
        assertEquals(990, item2?.counter2)
    }

    @Test
    fun testUniqueConflictOnInsert() = suspendTest {
        val table = db.table<UniqueModel>()
        val a = UniqueModel("test")
        val b = UniqueModel("test")
        table.delete { everything }
        table.insert(a)
        assertFailsWith<DuplicateKeyDbException> {
            table.insert(b)
        }
    }

    @Test
    fun testUpsert() = suspendTest {
        val table = db.table<UpsertTestModel>()
        val a = UpsertTestModel("test", "a", _id = DbKey("000000000000000000000001"))
        val b = UpsertTestModel("test", "b", _id = DbKey("000000000000000000000002"))
        table.delete { everything }
        table.insert(a)
        val c = table.upsert(b)
        //println(table.find { everything }.joinToString("\n"))
        assertEquals(1, table.find { everything }.count())
        assertEquals("UpsertTestModel(name=test, value=a, _id=000000000000000000000001)", a.toString())
        assertEquals("UpsertTestModel(name=test, value=b, _id=000000000000000000000002)", b.toString())
        assertEquals("UpsertTestModel(name=test, value=b, _id=000000000000000000000001)", c.toString())
    }

    data class UpsertTestModel(
            @DbUnique
            val name: String,
            val value: String,
            override val _id: DbKey = DbKey()
    ) : DbModel

    data class UniqueModel(
            @DbUnique
            val name: String,
            override val _id: DbKey = DbKey()
    ) : DbModel

    data class MyCounterTable(
            override val _id: DbKey = DbKey(),
            val counter1: Int = 1000,
            val counter2: Int = 1000
    ) : DbModel

    data class MyTestTable(
            override val _id: DbKey = DbKey(),
            val uuid: UUID,
            val date: Date,
            val long: Long,
            val int: Int,
            val string: String,
            val listString: List<String>,
            val mapString: Map<String, Any?>
    ) : DbModel
}