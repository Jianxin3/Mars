package net.minecraft.util;

import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CartesianProductUtil {

    private CartesianProductUtil() {
    }

    public static <T> List<List<T>> cartesianProduct(List<Iterable<T>> lists) {
        // incoming data is not null
        if (lists == null) return Collections.emptyList();
        // stream of lists Stream<List<T>>
        return lists.stream()
                // enable parallel mode
                .parallel()
                // discard null and empty lists
                .filter(list -> list != null && !Iterables.isEmpty(list))
                // reduce the stream of lists into one list, get a Cartesian product
                // reduce(identity, accumulator, combiner)
                .reduce( // intermediate result, contains one empty value
                        Collections.singletonList(Collections.emptyList()),
                        // bypass the received lists and supplement the intermediate result
                        // with their elements, at each step obtain a new intermediate result
                        (result, list) -> {
                            // next intermediate result
                            List<List<T>> nResult = new ArrayList<>(result.size() * 20);
                            // rows of the current intermediate result
                            for (List<T> row : result) {
                                // elements of the current list
                                for (T el : list) {
                                    // a new row for the next intermediate result
                                    List<T> nRow = new ArrayList<>(row.size() + 1);
                                    // add the current row
                                    nRow.addAll(row);
                                    // add the current element
                                    nRow.add(el);
                                    // add to the next intermediate result
                                    nResult.add(nRow);
                                }
                            }
                            // pass to the next iteration
                            return nResult;
                        },
                        // is used in parallel mode, combines the results of the work
                        // of streams, obtains the Cartesian product of the results
                        (result1, result2) -> {
                            // combined result
                            List<List<T>> result = new ArrayList<>(result1.size() * result2.size());
                            // bypass the results
                            for (List<T> comb1 : result1) {
                                for (List<T> comb2 : result2) {
                                    // add up the combinations
                                    List<T> comb = new ArrayList<>(comb1.size() + comb2.size());
                                    comb.addAll(comb1);
                                    comb.addAll(comb2);
                                    result.add(comb);
                                }
                            }
                            return result;
                        });
    }
}