package com.halotukozak.alpaca.plugin.toolwindow

import com.halotukozak.alpaca.plugin.grammar.ResolvedGrammar
import com.halotukozak.alpaca.plugin.grammar.resolveGrammarForFile
import com.halotukozak.alpaca.plugin.grammar.sourceFileCandidates
import com.halotukozak.alpaca.plugin.icons.AlpacaIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * "Alpaca Grammar": the whole of the current file's grammar (every token, every production
 * alternative), browsable in one place -- unlike Structure View (this file's own parse tree) or
 * Quick Documentation (one node's rule at a time). Refreshes on every editor selection change to
 * always show the grammar of whichever file is now on top. A nonterminal referenced inside a
 * production's right-hand side is itself expandable: clicking it lazily reveals that nonterminal's
 * own alternatives (see [GrammarTreeNode.expandable]), so exploring a recursive grammar (`Expr ->
 * Expr '+' Expr`) only ever grows as deep as actually clicked. Double-clicking (or pressing Enter
 * on) a token or production row jumps to the `lexer{...}`/`parser` rule that defines it, when the
 * export recorded one (see [GrammarTreeNode.sourceFile]).
 */
class AlpacaGrammarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = GrammarPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    panel.showGrammarFor(event.newFile)
                }
            },
        )
        panel.showGrammarFor(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }
}

/** Marks a Swing tree node added purely so an unexpanded [GrammarTreeNode.expandable] node shows
 *  an expand arrow; swapped out for the real alternatives the moment it's actually expanded. */
private object LoadingPlaceholder

private class GrammarPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val placeholder = JBLabel("Open an Alpaca-defined file to see its grammar.", SwingConstants.CENTER)
    private var resolved: ResolvedGrammar? = null
    private val tree =
        Tree().apply {
            isRootVisible = true
            cellRenderer = GrammarTreeCellRenderer()
            addTreeWillExpandListener(
                object : TreeWillExpandListener {
                    override fun treeWillExpand(event: TreeExpansionEvent) = expandLazily(event)

                    override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
                },
            )
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount != 2) return
                        val jtree = e.source as? JTree ?: return
                        val path = jtree.getPathForLocation(e.x, e.y) ?: return
                        navigateToSource(path.lastPathComponent as? DefaultMutableTreeNode)
                    }
                },
            )
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode != KeyEvent.VK_ENTER) return
                        val jtree = e.source as? JTree ?: return
                        navigateToSource(jtree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)
                    }
                },
            )
        }

    init {
        add(placeholder, BorderLayout.CENTER)
    }

    fun showGrammarFor(file: VirtualFile?) {
        resolved = file?.let { resolveGrammarForFile(project, it) }
        removeAll()
        val currentResolved = resolved
        if (currentResolved == null) {
            add(placeholder, BorderLayout.CENTER)
        } else {
            tree.model = DefaultTreeModel(toSwingTree(buildGrammarTree(currentResolved)))
            // Only the root: a grammar with hundreds of productions (e.g. the Scala one this
            // project's own parser targets) would otherwise dump every alternative open at once.
            tree.expandRow(0)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun expandLazily(event: TreeExpansionEvent) {
        val treeNode = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val grammarNode = treeNode.userObject as? GrammarTreeNode ?: return
        if (!grammarNode.expandable) return
        val onlyChild = treeNode.firstChild as? DefaultMutableTreeNode ?: return
        if (onlyChild.userObject !== LoadingPlaceholder) return

        val currentResolved = resolved ?: return
        treeNode.removeAllChildren()
        for (alternative in alternativesOf(grammarNode.primaryText, currentResolved)) {
            treeNode.add(toSwingTree(alternative))
        }
        (tree.model as DefaultTreeModel).nodeStructureChanged(treeNode)
    }

    /** Opens the `lexer{...}`/`parser` rule [treeNode] was defined by, if the export recorded a
     *  source location for it (see [GrammarTreeNode.sourceFile]) and the file can still be found --
     *  a no-op otherwise (a moved/renamed source file, a project opened elsewhere than the root the
     *  path is relative to). A relative path is tried against the project base dir, then each
     *  content root (see [sourceFileCandidates]). */
    private fun navigateToSource(treeNode: DefaultMutableTreeNode?) {
        val node = treeNode?.userObject as? GrammarTreeNode ?: return
        val sourceFile = node.sourceFile ?: return
        val sourceLine = node.sourceLine ?: return
        val roots =
            listOfNotNull(project.basePath?.let(Path::of)) +
                ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.fileSystem.getNioPath(it) }
        val fileSystem = LocalFileSystem.getInstance()
        val virtualFile =
            sourceFileCandidates(sourceFile, roots).firstNotNullOfOrNull { candidate ->
                fileSystem.findFileByNioFile(candidate) ?: fileSystem.refreshAndFindFileByNioFile(candidate)
            } ?: return
        OpenFileDescriptor(project, virtualFile, sourceLine, 0).navigate(true)
    }

    private fun toSwingTree(node: GrammarTreeNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).apply {
            if (node.expandable && node.children.isEmpty()) {
                add(DefaultMutableTreeNode(LoadingPlaceholder))
            } else {
                for (child in node.children) add(toSwingTree(child))
            }
        }
}

/** Renders a [GrammarTreeNode]: bold for a category/nonterminal heading, the pattern or
 *  right-hand side dimmed as a secondary segment -- the same role Structure View's location
 *  string plays -- instead of one flat string. */
private class GrammarTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject as? GrammarTreeNode ?: return
        icon = AlpacaIcons.FILE
        append(node.primaryText, if (node.bold) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        node.secondaryText?.let {
            append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
