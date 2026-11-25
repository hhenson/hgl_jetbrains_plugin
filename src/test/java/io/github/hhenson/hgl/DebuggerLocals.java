package io.github.hhenson.hgl;

import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueGroup;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodePresentationConfigurator.ConfigurableXValueNodeImpl;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil;

import javax.swing.Icon;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Ask the current frame for the same children and presentations as the Variables tree. */
final class DebuggerLocals implements XCompositeNode {
    private final Set<String> names;
    final Map<String, String> values = new ConcurrentHashMap<>();

    DebuggerLocals(Set<String> names) { this.names = names; }

    @Override public void addChildren(XValueChildrenList children, boolean last) {
        for (XValueGroup group : children.getTopGroups()) group.computeChildren(this);
        for (XValueGroup group : children.getBottomGroups()) group.computeChildren(this);
        for (int i = 0; i < children.size(); ++i) {
            String name = children.getName(i);
            if (!names.contains(name)) continue;
            children.getValue(i).computePresentation(new ConfigurableXValueNodeImpl() {
                @Override public void applyPresentation(Icon icon, XValuePresentation value, boolean hasChildren) {
                    values.put(name, XValuePresentationUtil.computeValueText(value));
                }
                @Override public boolean isObsolete() { return false; }
            }, XValuePlace.TREE);
        }
    }

    @Override public boolean isObsolete() { return false; }
    @Override public void tooManyChildren(int remaining) { throw new AssertionError("Expected all locals"); }
    @Override public void setAlreadySorted(boolean sorted) {}
    @Override public void setMessage(String text, Icon icon, SimpleTextAttributes style, XDebuggerTreeNodeHyperlink link) {}
    @Override public void setErrorMessage(String message) { throw new AssertionError(message); }
    @Override public void setErrorMessage(String message, XDebuggerTreeNodeHyperlink link) { setErrorMessage(message); }
}
