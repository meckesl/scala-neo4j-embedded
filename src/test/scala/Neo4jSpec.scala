import java.io.File
import java.util

import org.neo4j.graphdb.index.UniqueFactory
import org.neo4j.graphdb._
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.neo4j.graphdb.index.UniqueFactory.UniqueNodeFactory
import org.neo4j.graphdb.traversal.Uniqueness
import org.scalatest._
import scala.collection.JavaConversions._
import scala.util.Random

object DbServer {
  def runDb: GraphDatabaseService = {
    val neoStore: File = new File("src/test/neostore")
    if (!neoStore.exists)
      neoStore.mkdir
    new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(neoStore).newGraphDatabase
  }
}

import DbServer._

class Neo4jSpec extends FlatSpec with Matchers {

  "Neo4j-embedded" should "start" in {
    val grapdDb = runDb
    grapdDb.shutdown()
  }

  "Neo4j-transaction" should "work nicely" in {
    val grapdDb = runDb
    var tx: Transaction = null
    try {
      tx = grapdDb.beginTx
      tx.success()
    } finally {
      tx.close()
    }
    grapdDb.shutdown()
  }

  "Neo4j-relationships" should "work" in {
    val grapdDb = runDb

    object RelTypes extends Enumeration {
      type RelTypes = Value
      val KNOWS = Value

      implicit def conv(rt: RelTypes): RelationshipType = new RelationshipType() {
        def name = rt.toString
      }
    }

    var tx: Transaction = null
    try {
      tx = grapdDb.beginTx
      val firstNode = grapdDb.createNode()
      firstNode.setProperty("message", "Hello, ")
      val secondNode = grapdDb.createNode()
      secondNode.setProperty("message", "World!")
      val relationship = firstNode.createRelationshipTo(secondNode, RelTypes.KNOWS)
      relationship.setProperty("message", "brave Neo4j ")
      tx.success()
    } finally {
      tx.close()
    }

    grapdDb.shutdown()
  }

  "Neo4j-with-scala" should "be fun" in {
    val graphDb = runDb

    object RelTypes extends Enumeration {
      type RelTypes = Value
      val NEIGHBOR = Value

      implicit def conv(rt: RelTypes) = new RelationshipType() {
        def name = rt.toString
      }
    }

    val extendedCode : (Char) => Boolean = (c:Char) => (c <= 32 || c >= 127)
    val text = scala.io.Source.fromFile("src/test/resources/testInput.txt").mkString.filterNot(extendedCode).toLowerCase

    var tx: Transaction = null
    var ucnf: UniqueNodeFactory = null

    /** **************************/
    /** CREATE GRAPH FROM TEXT **/
    try {
      tx = graphDb.beginTx

      ucnf = new UniqueFactory.UniqueNodeFactory(graphDb, "chars") {
        override def initialize(n: Node, prop: util.Map[String, AnyRef]): Unit = {
          n.addLabel(DynamicLabel.label("Node"))
          n.setProperty("char", prop.get("char"))
        }
      }

      // Map Unique Char Nodes from input text
      text.zipWithIndex.foreach(
        c => {
          val ucn = ucnf.getOrCreate("char", c._1)
          val next = c._2 + 1
          if (text.length > next)
            ucn.createRelationshipTo(ucnf.getOrCreate("char", text(next)), RelTypes.NEIGHBOR)
        }
      )

      tx.success()

    } finally {
      tx.close()
    }

    /** **************************/
    /** TRAVERSE GRAPH **/
    try {
      tx = graphDb.beginTx

      val neighborTraversal = graphDb.traversalDescription()
        .depthFirst()
        .relationships(RelTypes.NEIGHBOR)
        .uniqueness(Uniqueness.NODE_GLOBAL)

      val randomStartNode = ucnf.getOrCreate("char", text(Random.nextInt(text.length)))

      val traversed = neighborTraversal.traverse(randomStartNode).nodes()

      println(s"traversed: $traversed")

      traversed.toList.sortWith(_.getDegree < _.getDegree).foreach(
        n => {
          val char = n.getProperty("char")
          val degree = n.getDegree.toString
          val rls = n.getRelationships(RelTypes.NEIGHBOR)
            .map(_.getEndNode)
            .map(_.getProperty("char"))
            .toSet.mkString("[", ",", "]")
          println(s"'$char': degree=$degree rel=$rls")
        }
      )
      tx.success()

    } finally {
      tx.close()
    }

    graphDb.shutdown()
  }

}