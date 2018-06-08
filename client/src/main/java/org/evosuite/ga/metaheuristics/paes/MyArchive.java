package org.evosuite.ga.metaheuristics.paes;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.metaheuristics.paes.Grid.GridLocation;
import org.evosuite.ga.metaheuristics.paes.Grid.GridNode;
import org.evosuite.ga.metaheuristics.paes.Grid.GridNodeInterface;

import java.util.*;

/**
 * PAES Archive to store non-dominated {@link Chromosome} in a Genetic algorithm.
 *
 * Created by Sebastian on 10.04.2018.
 */
public class MyArchive<C extends Chromosome> implements Archive<C> {
    private static final boolean USE_RECURSIVE_GRID_CROWDED = false;
    private static boolean USE_BEST_SCORE = false;
    private static final int MAX_SIZE = 100;
    private static final int GRID_LAYER_DEPTH = 10;
    private GridNodeInterface<C> grid;
    private List<C> archivedChromosomes = new ArrayList<>();
    private List<FitnessFunction<?>> fitnessFunctions;

    /**
     * Constructor for a new Archive for a {@param gridDimension}-dimensional space
     * from {@param min_value} to {@param max_value}
     *
     * @param fitnessFunctions
     * @param min_value
     * @param max_value
     */
    public MyArchive(Set<FitnessFunction<?>> fitnessFunctions, double min_value, double max_value){
        this.fitnessFunctions = new ArrayList<>();
        this.fitnessFunctions.addAll(fitnessFunctions);
        Map<FitnessFunction<?>,Double> lowerBounds = new LinkedHashMap<>();
        Map<FitnessFunction<?>, Double> upperBounds = new LinkedHashMap<>();
        for(FitnessFunction<?> ff : fitnessFunctions){
            lowerBounds.put(ff, min_value);
            upperBounds.put(ff, max_value);
        }
        grid = new GridNode<>(lowerBounds, upperBounds, MyArchive.GRID_LAYER_DEPTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(C c) {
        this.removeDominated(c);
        for (C chromosome: archivedChromosomes)
            if(chromosome.dominates(c))
                return false;
        if(this.archivedChromosomes.size() < MyArchive.MAX_SIZE){
            this.archivedChromosomes.add(c);
            this.grid.add(c);
            return true;
        } else {
            GridLocation<C> mostCrowded =
                    MyArchive.USE_RECURSIVE_GRID_CROWDED ? grid.recursiveMostCrowdedRegion() : grid.mostCrowdedRegion();
            if(mostCrowded.isInBounds(c.getCoverageValues()))
                return false;
            GridLocation<C> region = grid.region(c);
            if(region != null && region.count() >= mostCrowded.count() && !MyArchive.USE_RECURSIVE_GRID_CROWDED)
                return false;
            C deleted = mostCrowded.getAll().get(0);
            if(region != null)
                region.add(c);
            else
                grid.add(c);
            grid.delete(deleted);
            archivedChromosomes.remove(deleted);
            archivedChromosomes.add(c);
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<C> getChromosomes() {
        return archivedChromosomes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean decide(C candidate, C current) {
        if(MyArchive.USE_BEST_SCORE){
            int candidateBestScoreCount = this.getBestScoreCount(candidate);
            int currentBestScoreCount = this.getBestScoreCount(current);
            if(candidateBestScoreCount > currentBestScoreCount)
                return true;
            else if(currentBestScoreCount > candidateBestScoreCount)
                return false;
        }
        int dif = this.grid.decide(candidate, current, MyArchive.USE_RECURSIVE_GRID_CROWDED);
        return dif > 0;
    }

    private int getBestScoreCount(C chromosome) {
        Map<FitnessFunction<?>, Double> coverageValues = chromosome.getCoverageValues();
        LinkedHashMap<FitnessFunction<?>, Boolean> defeated = new LinkedHashMap<>();
        int count = 0;
        for(C c : archivedChromosomes){
            for(FitnessFunction<?> ff : coverageValues.keySet()){
                if(!defeated.get(ff) && (chromosome.getCoverage(ff) > c.getCoverage(ff))) {
                    defeated.put(ff, true);
                    ++count;
                }
            }
        }
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeDominated(C c) {
        List<C> dominated = new ArrayList<>();
        for (C chromosome: archivedChromosomes)
            if(c.dominates(chromosome))
                dominated.add(chromosome);
        archivedChromosomes.removeAll(dominated);
        grid.deleteAll(dominated);
    }
}


