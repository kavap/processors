package org.clulab.odin.impl

import org.clulab.struct.Interval
import org.clulab.processors.Document
import org.clulab.odin._

trait Extractor {
  def name: String
  def labels: Seq[String]
  def label: String = labels.head  // the first label in the sequence is the default
  def priority: Priority
  def keep: Boolean  // should we keep mentions generated by this extractor?
  def action: Action

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention]

  def findAllIn(doc: Document, state: State): Seq[Mention] = for {
    i <- 0 until doc.sentences.size
    m <- findAllIn(i, doc, state)
  } yield m
}

class TokenExtractor(
    val name: String,
    val labels: Seq[String],
    val priority: Priority,
    val keep: Boolean,
    val action: Action,
    val pattern: TokenPattern
) extends Extractor {

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention] = {
    val results = pattern.findAllIn(sent, doc, state)
    val mentions = for (r <- results) yield mkMention(r, sent, doc)
    action(mentions, state)
  }

  def mkMention(r: TokenPattern.Result, sent: Int, doc: Document): Mention =
    r.groups.keys find (_ equalsIgnoreCase "trigger") match {
      case Some(triggerKey) =>
        // having several triggers in the same rule is not supported
        // the first will be used and the rest ignored
        val int = r.groups(triggerKey).head
        val trigger = new TextBoundMention(labels, int, sent, doc, keep, name)
        val groups = r.groups - triggerKey transform { (argName, intervals) =>
          intervals.map(i => new TextBoundMention(labels, i, sent, doc, keep, name))
        }
        val args = mergeArgs(groups, r.mentions)
        new EventMention(labels, r.interval, trigger, args, Map.empty, sent, doc, keep, name)
      case None if r.groups.nonEmpty || r.mentions.nonEmpty =>
        // result has arguments and no trigger, create a RelationMention
        val groups = r.groups transform { (argName, intervals) =>
          intervals.map(i => new TextBoundMention(labels, i, sent, doc, keep, name))
        }
        val args = mergeArgs(groups, r.mentions)
        new RelationMention(labels, r.interval, args, Map.empty, sent, doc, keep, name)
      case None =>
        // result has no arguments, create a TextBoundMention
        new TextBoundMention(labels, r.interval, sent, doc, keep, name)
    }

  type Args = Map[String, Seq[Mention]]
  def mergeArgs(m1: Args, m2: Args): Args = {
    val merged = for (name <- m1.keys ++ m2.keys) yield {
      val args = m1.getOrElse(name, Vector.empty) ++ m2.getOrElse(name, Vector.empty)
      name -> args.distinct
    }
    merged.toMap
  }
}

class DependencyExtractor(
    val name: String,
    val labels: Seq[String],
    val priority: Priority,
    val keep: Boolean,
    val action: Action,
    val pattern: DependencyPattern
) extends Extractor {

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention] = {
    val mentions = pattern.getMentions(sent, doc, state, labels, keep, name)
    action(mentions, state)
  }
}


class MultiSentenceExtractor(
  val name: String,
  val labels: Seq[String],
  val priority: Priority,
  val keep: Boolean,
  val action: Action,
  // the maximum number of sentences to look ahead for pattern2
  val window: Int,
  val pattern1: TokenExtractor,
  val pattern2: TokenExtractor,
  val arg1: String,
  val arg2: String
) extends Extractor {

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention] = {
    pattern1.findAllIn(sent, doc, state) match {
      case Nil => Nil
      case pattern1Mentions =>
        val nextSent = sent + 1
        val mentions = for {
          i <- nextSent until nextSent + window
          // is the sentence within the allotted window?
          if doc.sentences.length < i
          // attempt to match pattern2
          pattern2Mentions = pattern2.findAllIn(i, doc, state)
          before <- pattern1Mentions
          after <- pattern2Mentions
        } yield mkMention(before, after)

        action(mentions, state)
    }
  }

  def mkMention(before: Mention, after: Mention): Mention = {
    val args = Map(arg1 -> Seq(before), arg2 -> Seq(after))
    // FIXME: we should redo Mention's interval (and sentence)
    new RelationMention(labels, before.tokenInterval, args, Map.empty, before.sentence, before.document, keep, name)
  }
}