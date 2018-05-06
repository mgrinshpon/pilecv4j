package com.jiminger.gstreamer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.GhostPad;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jiminger.gstreamer.guard.GstScope;

/**
 *  This class can be used to build either a {@link Bin} or a {@link Pipeline} using a builder pattern.
 *  It encapsulates many of the boilerplate manipulations including naming, adding and linking elements.
 */
public class BinManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BinManager.class);

    private Branch current = new Branch();
    private final Branch primary = current;

    private GstScope scope = null;
    private Bin managed = null;

    private int ghostPadNum = 0;

    /**
     * Add an element that has dynamic pads. This will manage linking the dynamic pads
     * as they're being added to the element.
     */
    public BinManager delayed(final Element element) {
        current.delayed(element);
        return this;
    }

    /**
     * Add an element that has dynamic pads. This will manage linking the dynamic pads
     * as they're being added to the element.
     */
    public BinManager delayed(final String element) {
        current.delayed(element);
        return this;
    }

    /**
     * Add an element that has dynamic pads. This will manage linking the dynamic pads
     * as they're being added to the element.
     */
    public BinManager delayed(final String element, final String name) {
        current.delayed(element, name);
        return this;
    }

    /**
     * If you want to supply your own link logic then you can call this. The most recently
     * added element must be "delayed" or this will throw an IllegalStateException
     */
    public BinManager dynamicLink(final DynamicLink linker) {
        current.dynamicLink(linker);
        return this;
    }

    /**
     * Add an element that has static pads. 
     */
    public BinManager make(final String element) {
        current.make(element);
        return this;
    }

    /**
     * Add an element that has static pads. 
     */
    public BinManager make(final String element, final String name) {
        current.make(element, name);
        return this;
    }

    /**
     * Add a caps filter statement. Also see {@link CapsBuilder}.
     */
    public BinManager caps(final String caps) {
        current.caps(caps);
        return this;
    }

    /**
     * Add a caps filter statement. Also see {@link CapsBuilder}.
     */
    public BinManager caps(final Caps caps) {
        current.caps(caps);
        return this;
    }

    /**
     * Add an element that has static pads. 
     */
    public BinManager add(final Element e) {
        current.add(e);
        return this;
    }

    /**
     * Set a property on the most recently added element.
     */
    public BinManager with(final String name, final Object value) {
        current.with(name, value);
        return this;
    }

    /**
     * Split this element chain in multiple directions. 
     */
    public BinManager tee(final Branch... branches) {
        return tee(null, branches);
    }

    /**
     * Split this element chain in multiple directions using a tee with the
     * given name.
     */
    public BinManager tee(final String teeName, final Branch... branches) {
        current.connectDownstreams(teeName, false, branches);
        findContinuation(branches);
        return this;
    }

    /**
     * Split this element chain in multiple directions using a tee.
     *  Automatically include a multiqueue as the first element of
     * each branch being added to the tee.
     */
    public BinManager teeWithMultiqueue(final Branch... branches) {
        return teeWithMultiqueue(null, branches);
    }

    /**
     * Split this element chain in multiple directions using a tee with the
     * given name. Automatically include a multiqueue as the first element of
     * each branch being added to the tee.
     */
    public BinManager teeWithMultiqueue(final String teeName, final Branch... branches) {
        current.connectDownstreams(teeName, true, branches);
        findContinuation(branches);
        return this;
    }

    /**
     * A BinBuilder starts with a primary branch. if you need to traverse the internal
     * shape of the elements you can retrieve the primary branch 
     */
    public Branch getPrimaryBranch() {
        return primary;
    }

    /**
     * Add the full chain of elements to the given Bin/Pipeline
     */
    public BinManager addAllTo(final Bin pipe) {
        addFullChainTo(pipe, primary);
        return this;
    }

    /**
     * Internally link all the entire chain if Elements. These must have
     * already been added to a Bin/Pipeline to work correctly.
     */
    public BinManager linkAll() {
        Branch.linkFullChainOfBranches(primary);
        return this;
    }

    /**
     * Manage any generated Bin in the given scope.
     */
    public BinManager scope(final GstScope scope) {
        this.scope = scope;
        return this;
    }

    /**
     * Convert the current builder to a {@link Pipeline} managed by the
     * given scope. That is, it will be automatically disposed alone with
     * all of the elements created as part of this BinBuilder when the
     * scope is closed.
     */
    public Pipeline buildPipeline(final GstScope scope) {
        return scope.manage(buildPipeline());
    }

    /**
     * Convert the current builder to a {@link Pipeline}
     */
    public Pipeline buildPipeline() {
        final Pipeline pipe = new Pipeline() {
            @Override
            public void dispose() {
                LOGGER.debug("disposing " + this + " with a ref count of " + this.getRefCount());
                super.dispose();
                disposeAll(primary);
            }
        };
        return build(pipe, false);
    }

    /**
     * Convert the current builder to a {@link Bin}. This will also manage
     * the GhostPads. 
     */
    public Bin buildBin() {
        return buildBin(null, true);
    }

    /**
     * Convert the current builder to a {@link Bin} with the given name. This will also manage
     * the GhostPads. 
     */
    public Bin buildBin(final String binName) {
        return buildBin(binName, true);
    }

    /**
     * Convert the current builder to a {@link Bin} with the given name. If requested, this will 
     * also manage the GhostPads. 
     */
    public Bin buildBin(final String binName, final boolean ghostPads) {
        final Bin bin = binName == null ? new Bin() {
            @Override
            public void dispose() {
                LOGGER.debug("disposing " + this + " with a ref count of " + this.getRefCount());
                super.dispose();
                disposeAll(primary);
            }
        } : new Bin(binName) {
            @Override
            public void dispose() {
                LOGGER.debug("disposing " + this + " with a ref count of " + this.getRefCount());
                super.dispose();
                disposeAll(primary);
            }
        };
        return scope == null ? build(bin, ghostPads) : scope.manage(build(bin, ghostPads));
    }

    /**
     * Return the list of all the source endpoint elements being managed. If there are no branches
     * this should contain 1 element.
     */
    public List<Element> getAllSrcElements() {
        final List<Element> ret = new ArrayList<>();
        appendEnds(primary, ret);
        return ret;
    }

    /**
     * Get a stream with all of the branches managed by this Bin. This will always have at least
     * the "primary" Branch.
     */
    public Stream<Branch> allBranches() {
        return primary.allBranches();
    }

    public void addAndLinkNewBranch(final Branch branchToAppendForkTo, final Branch fork, final boolean ghostPad) {
        if (managed == null)
            throw new IllegalStateException(
                    "Cannot addAndLinkNewBranch using a BinManager that isn't managing a Bin. You need to call one of the build methods first.");
        branchToAppendForkTo.addAllTo(managed);
        branchToAppendForkTo.linkNewBranch(fork);

        if (ghostPad)
            ghostSrcs(managed, fork.getLastElement().getSrcPads());
    }

    public Pad fork(final Branch branchToAppendForkTo, final boolean ghostPad) {
        if (managed == null)
            throw new IllegalStateException(
                    "Cannot addAndLinkNewBranch using a BinManager that isn't managing a Bin. You need to call one of the build methods first.");

        final Pad teePad = branchToAppendForkTo.forkYou();

        if (ghostPad) {
            final GhostPad gpad = new GhostPad(teePad.getName() + "_" + ghostPadNum, teePad);
            ghostPadNum++;
            managed.addPad(gpad);
            return gpad;
        } else
            return teePad;
    }

    protected <T extends Bin> T build(final T pipe, final boolean ghostPads) {
        addFullChainTo(pipe, primary);
        Branch.linkFullChainOfBranches(primary);
        if (ghostPads)
            createGhostPads(pipe);

        managed = pipe;

        return pipe;
    }

    private BinManager createGhostPads(final Bin bin) {
        final Element first = primary.getSinkElement();

        if (first != null) {
            final List<Pad> pads = first.getSinkPads();
            for (final Pad pad : pads)
                bin.addPad(new GhostPad(pad.getName(), pad));
        }

        final List<Element> lastList = getAllSrcElements();

        if (lastList != null) {
            for (final Element last : lastList)
                ghostSrcs(bin, last.getSrcPads());
        }

        return this;
    }

    private void ghostSrcs(final Bin bin, final List<Pad> pads) {
        for (final Pad pad : pads) {
            final GhostPad gpad = new GhostPad(pad.getName() + "_" + ghostPadNum, pad);
            ghostPadNum++;
            bin.addPad(gpad);
        }

    }

    private void findContinuation(final Branch[] branches) {
        final List<Branch> continuations = Arrays.stream(branches).filter(b -> b.continueThisBranch).collect(Collectors.toList());
        if (continuations.size() > 1)
            throw new IllegalStateException("You cannot set more than one " + Branch.class.getSimpleName() + " as a continuation of the parent "
                    + BinManager.class.getSimpleName());
        if (continuations.size() == 1)
            current = continuations.get(0);
    }

    private static void appendEnds(final Branch branch, final List<Element> ret) {
        if (branch.sinks.size() == 0) // if this isn't terminated in a tee
            ret.add(branch.getLastElement());
        else
            branch.sinks.stream().forEach(b -> appendEnds(b, ret));
    }

    private static void disposeAll(final Branch cur) {
        final List<Branch> branches = cur.sinks;

        for (int b = branches.size() - 1; b >= 0; b--)
            disposeAll(branches.get(b));

        cur.disposeAll();
    }

    private static void addFullChainTo(final Bin pipe, final Branch primary) {
        primary.addAllTo(pipe);
        final List<Branch> next = primary.sinks;
        if (next.size() > 0) {
            for (final Branch c : next)
                addFullChainTo(pipe, c);
        }
    }
}